package me.rerere.rikkahub.data.ai.subscription

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.common.platform.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionAuthManagerTest {
    private lateinit var store: SecureStore
    private lateinit var secrets: SecretKeyManager
    private val setting = ProviderSetting.OpenAI(subscriptionAccount = SubscriptionAccountType.CHATGPT)
    private fun session(expiry: Long = 1) = SubscriptionSession(SubscriptionAccountType.CHATGPT, "client",
        "old-access", "old-refresh", subject = "account", scope = "chatgpt.tokens.use.direct", expiresAt = expiry)

    @Before fun setup() {
        store = SecureStore(RuntimeEnvironment.getApplication())
        store.clearAll()
        secrets = SecretKeyManager(store)
        secrets.setSubscriptionSession(setting.id, JsonInstant.encodeToString(session()))
    }

    @Test fun concurrentRequestsRotateRefreshTokenOnceAndPersistThePair() = runBlocking {
        val requests = mutableListOf<PlatformHttpRequest>()
        val client = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
                if (request.method == "GET") return discovery()
                requests += request
                delay(10)
                return PlatformHttpResponse(200, body = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""".encodeToByteArray())
            }
            override fun streamEvents(request: PlatformHttpRequest) = emptyFlow<PlatformServerEvent>()
        }
        val manager = SubscriptionAuthManager(client, secrets)
        val results = coroutineScope { (1..12).map { async(Dispatchers.Default) { manager.accessToken(setting) } }.awaitAll() }
        assertTrue(results.all { it == "new-access" })
        assertEquals(1, requests.size)
        assertEquals("https://auth.openai.com/api/accounts/oauth/token", requests.single().url)
        assertTrue(requests.single().body?.decodeToString()?.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1") == true)
        val stored = JsonInstant.decodeFromString<SubscriptionSession>(requireNotNull(secrets.getSubscriptionSession(setting.id)))
        assertEquals("new-access", stored.accessToken)
        assertEquals("new-refresh", stored.refreshToken)
        assertEquals("account", stored.subject)
    }

    @Test fun sessionDoesNotEnterSettingsExportsAndDeletingProviderRemovesIt() {
        val settings = Settings(providers = listOf(setting))
        val export = JsonInstant.encodeToString(secrets.populateSecretsForExport(settings))
        assertFalse(export.contains("old-access"))
        assertFalse(export.contains("old-refresh"))
        secrets.handleExplicitSecretDeletions(settings, settings.copy(providers = emptyList()))
        assertNull(secrets.getSubscriptionSession(setting.id))
    }

    @Test fun transientRefreshFailurePreservesPreviousCredentialsAndSignOutRemovesThem() = runBlocking {
        val client = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest) =
                if (request.method == "GET") discovery() else PlatformHttpResponse(503, body = """{"error":"temporarily_unavailable"}""".encodeToByteArray())
            override fun streamEvents(request: PlatformHttpRequest) = emptyFlow<PlatformServerEvent>()
        }
        val manager = SubscriptionAuthManager(client, secrets)
        assertTrue(runCatching { manager.accessToken(setting) }.isFailure)
        assertEquals("old-refresh", JsonInstant.decodeFromString<SubscriptionSession>(requireNotNull(secrets.getSubscriptionSession(setting.id))).refreshToken)
        manager.signOut(setting.id)
        assertNull(manager.accountLabel(setting))
        assertTrue(runCatching { manager.accessToken(setting) }.isFailure)
    }
    @Test fun hostIdUsesUrnFormatAndMigratesTheExistingUuidStably() {
        val bare = "00000000-0000-4000-8000-000000000000"
        store.putSecret("subscription_host_id", bare)
        assertEquals("urn:uuid:$bare", secrets.subscriptionHostId())
        assertEquals("urn:uuid:$bare", secrets.subscriptionHostId())
        store.removeSecret("subscription_host_id")
        val generated = secrets.subscriptionHostId()
        assertTrue(generated.matches(Regex("urn:uuid:[0-9a-f-]{36}")))
        assertEquals(generated, secrets.subscriptionHostId())
    }

    @Test fun terminalRefreshErrorClearsTokensButRetainsTheIssuedRegistration() = runBlocking {
        val client = object : PlatformHttpClient {
            override suspend fun execute(request: PlatformHttpRequest) = if (request.method == "GET") discovery()
                else PlatformHttpResponse(400, body = """{"error":"invalid_grant"}""".encodeToByteArray())
            override fun streamEvents(request: PlatformHttpRequest) = emptyFlow<PlatformServerEvent>()
        }
        val manager = SubscriptionAuthManager(client, secrets)
        assertTrue(runCatching { manager.accessToken(setting) }.isFailure)
        val saved = JsonInstant.decodeFromString<SubscriptionSession>(requireNotNull(secrets.getSubscriptionSession(setting.id)))
        assertEquals("client", saved.clientId)
        assertEquals("account", saved.subject)
        assertTrue(saved.accessToken.isEmpty())
        assertTrue(saved.refreshToken.isEmpty())
        assertNull(manager.accountLabel(setting))
    }

    private fun discovery() = PlatformHttpResponse(200, body = """{
        "issuer":"https://auth.openai.com",
        "authorization_endpoint":"https://auth.openai.com/api/accounts/authorize",
        "token_endpoint":"https://auth.openai.com/api/accounts/oauth/token",
        "jwks_uri":"https://auth.openai.com/.well-known/jwks.json"
    }""".encodeToByteArray())

}
