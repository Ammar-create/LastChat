package me.rerere.rikkahub.data.ai.subscription

import android.app.Application
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.common.http.urlDecode
import me.rerere.common.platform.*
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.data.datastore.SecureStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.io.encoding.Base64
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CodexSignInTest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val setting = ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT)
    private fun secrets(): SecretKeyManager {
        val store = SecureStore(RuntimeEnvironment.getApplication())
        store.clearAll()
        return SecretKeyManager(store)
    }
    private fun identity(nonce: String?): String {
        val header = base64.encode("""{"alg":"RS256","kid":"test"}""".encodeToByteArray())
        val payload = buildJsonObject {
            put("iss", "https://auth.openai.com"); put("aud", CodexOAuth.CLIENT_ID)
            put("sub", "subject"); put("email", "me@example.com")
            put("exp", Clock.System.now().epochSeconds + 3600)
            if (nonce != null) put("nonce", nonce)
            put("https://api.openai.com/auth", buildJsonObject {
                put("chatgpt_account_id", "account-123"); put("chatgpt_plan_type", "plus")
            })
        }
        val encoded = "$header.${base64.encode(payload.toString().encodeToByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(encoded.encodeToByteArray()) }.sign()
        return "$encoded.${base64.encode(signature)}"
    }
    private fun jwks(): String {
        val key = pair.public as RSAPublicKey
        return """{"keys":[{"kid":"test","kty":"RSA","n":"${base64.encode(key.modulus.toByteArray())}","e":"${base64.encode(key.publicExponent.toByteArray())}"}]}"""
    }
    private fun response(body: String) = PlatformHttpResponse(200, body = body.encodeToByteArray())
    private val discovery = """{"issuer":"https://auth.openai.com","authorization_endpoint":"https://auth.openai.com/api/accounts/authorize","token_endpoint":"https://auth.openai.com/api/accounts/oauth/token","jwks_uri":"https://auth.openai.com/.well-known/jwks.json"}"""

    @Test fun callbackCompletesButDnsFailureDuringKeyFetchResumesWithoutReusingConsumedCode() = runBlocking {
        val secrets = secrets()
        var nonce: String? = null
        var exchanges = 0
        var keyFetches = 0
        var browserOpens = 0
        val client = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse = when {
                request.url.endsWith("openid-configuration") -> response(discovery)
                request.url == CodexOAuth.TOKEN -> {
                    exchanges++
                    assertTrue(request.body!!.decodeToString().contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"))
                    response("""{"access_token":"access","refresh_token":"refresh","id_token":"${identity(nonce)}","expires_in":3600,"token_type":"Bearer"}""")
                }
                request.url.endsWith("jwks.json") -> {
                    if (++keyFetches == 1) throw UnknownHostException("auth.openai.com")
                    response(jwks())
                }
                else -> error(request.url)
            }
            override fun streamEvents(request: PlatformHttpRequest) = emptyFlow<PlatformServerEvent>()
        }
        val manager = SubscriptionAuthManager(client, secrets)
        val failed = runCatching {
            manager.signIn(setting) { prompt ->
                browserOpens++
                assertTrue(prompt.url.startsWith("https://auth.openai.com/oauth/authorize?"))
                val parameters = prompt.url.substringAfter('?').split('&').associate {
                    it.substringBefore('=').urlDecode() to it.substringAfter('=').urlDecode()
                }
                nonce = parameters["nonce"]
                assertEquals(CodexOAuth.CLIENT_ID, parameters["client_id"])
                // An unrelated callback must not consume the pending attempt.
                val bad = URL("${CodexOAuth.REDIRECT}?state=unrelated&code=bad").openConnection() as java.net.HttpURLConnection
                assertEquals(400, bad.responseCode); bad.disconnect()
                URL("${CodexOAuth.REDIRECT}?state=${parameters["state"]}&code=test-code").openStream().use { it.readBytes() }
            }
        }
        assertTrue(failed.exceptionOrNull() is UnknownHostException)
        assertNull(manager.accountLabel(setting))
        assertNotNull(secrets.getSubscriptionPending(setting.id))
        assertEquals(1, exchanges)
        manager.signIn(setting) { error("A consumed code must resume from its encrypted token checkpoint") }
        assertEquals(1, browserOpens)
        assertEquals(1, exchanges)
        assertEquals("me@example.com", manager.accountLabel(setting))
        assertTrue(manager.usesCodex(setting))
        assertEquals("account-123", manager.accountId(setting))
        assertNull(secrets.getSubscriptionPending(setting.id))
        val export = JsonInstant.encodeToString(secrets.populateSecretsForExport(Settings(providers = listOf(setting))))
        assertFalse(export.contains("test-code")); assertFalse(export.contains("\"refresh_token\"")); assertFalse(export.contains("access_token"))
    }

    @Test fun deviceLoginAndRefreshUseTheirOwnEncodingAndOptInDnsTransport() = runBlocking {
        val secrets = secrets()
        var primaryCalls = 0
        val requests = mutableListOf<PlatformHttpRequest>()
        val primary = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse { primaryCalls++; throw UnknownHostException("blocked") }
            override fun streamEvents(request: PlatformHttpRequest) = emptyFlow<PlatformServerEvent>()
        }
        val verifier = AndroidSubscriptionIdentity.randomValue()
        val fallback = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
                requests += request
                return when {
                    request.url.endsWith("openid-configuration") -> response(discovery)
                    request.url.endsWith("deviceauth/usercode") -> response("""{"device_auth_id":"device","user_code":"TEST-CODE","interval":"1"}""")
                    request.url.endsWith("deviceauth/token") -> response("""{"authorization_code":"code","code_verifier":"$verifier","code_challenge":"${AndroidSubscriptionIdentity.challenge(verifier)}"}""")
                    request.url.endsWith("jwks.json") -> response(jwks())
                    request.url == CodexOAuth.TOKEN -> if (request.mediaType == "application/json")
                        response("""{"access_token":"rotated","refresh_token":"rotated-refresh","expires_in":3600}""")
                    else response("""{"access_token":"access","refresh_token":"refresh","id_token":"${identity(null)}","expires_in":3600}""")
                    else -> error(request.url)
                }
            }
            override fun streamEvents(request: PlatformHttpRequest) = emptyFlow<PlatformServerEvent>()
        }
        val manager = SubscriptionAuthManager(primary, secrets, fallback)
        val setting = setting.copy(subscriptionDnsFallback = true)
        manager.signIn(setting, deviceCode = true) {
            assertEquals(CodexOAuth.DEVICE_VERIFY, it.url); assertEquals("TEST-CODE", it.code)
        }
        assertEquals("rotated", manager.refreshAccessToken(setting))
        val refresh = requests.last()
        assertEquals("application/json", refresh.mediaType)
        val body = JsonInstant.parseToJsonElement(refresh.body!!.decodeToString()).jsonObject
        assertEquals("refresh_token", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals(CodexOAuth.CLIENT_ID, body["client_id"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("resource"))
        assertEquals(0, primaryCalls)
        manager.signOut(setting.id)
        assertNull(manager.accountLabel(setting)); assertNull(secrets.getSubscriptionSession(setting.id))
    }
}
