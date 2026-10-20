package me.rerere.rikkahub.data.ai.subscription

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.ai.provider.providers.SubscriptionTokenSource
import me.rerere.ai.util.providerHttpError
import me.rerere.common.http.urlEncode
import me.rerere.common.http.urlHostOrNull
import me.rerere.common.platform.*
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
internal data class SubscriptionSession(
    val type: SubscriptionAccountType,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String = "",
    val idToken: String = "",
    val subject: String = "",
    val email: String = "",
    val scope: String = "",
    val expiresAt: Long,
    // Existing direct-plan sessions remain usable; new ChatGPT sign-ins use Codex like upstream.
    val protocol: String = "direct",
    val accountId: String = "",
    val plan: String = "",
)

@Serializable
internal data class PendingSubscriptionLogin(
    val code: String,
    val verifier: String,
    val nonce: String? = null,
    val redirect: String,
    val expiresAt: Long,
    val tokenResponse: String? = null,
)

data class SubscriptionLoginPrompt(val url: String, val code: String? = null)

/** Browser/device OAuth and refresh use unlogged clients and encrypted, atomic credential records. */
class SubscriptionAuthManager(
    private val client: PlatformHttpClient,
    private val secrets: SecretKeyManager,
    private val fallbackClient: PlatformHttpClient = client,
) : SubscriptionTokenSource {
    private val sessionLock = Mutex()
    private val loginLock = Mutex() // The registered browser callback has one fixed port.
    private fun http(setting: ProviderSetting.OpenAI) = if (setting.subscriptionDnsFallback) fallbackClient else client
    private fun session(id: Uuid): SubscriptionSession? = secrets.getSubscriptionSession(id)?.let {
        runCatching { JsonInstant.decodeFromString<SubscriptionSession>(it) }.getOrNull()
    }
    private fun pending(id: Uuid): PendingSubscriptionLogin? = secrets.getSubscriptionPending(id)?.let {
        runCatching { JsonInstant.decodeFromString<PendingSubscriptionLogin>(it) }.getOrNull()
    }?.takeIf { it.expiresAt > now() }
    private fun savePending(id: Uuid, value: PendingSubscriptionLogin) =
        secrets.setSubscriptionPending(id, JsonInstant.encodeToString(value))
    private fun save(id: Uuid, value: SubscriptionSession) =
        secrets.setSubscriptionSession(id, JsonInstant.encodeToString(value))

    suspend fun accountLabel(setting: ProviderSetting.OpenAI): String? = withContext(Dispatchers.IO) {
        session(setting.id)?.takeIf { it.type == setting.subscriptionAccount && it.accessToken.isNotBlank() }?.let {
            it.email.ifBlank { "${it.type.displayName} account connected" }
        }
    }
    override suspend fun usesCodex(setting: ProviderSetting.OpenAI): Boolean = withContext(Dispatchers.IO) {
        session(setting.id)?.protocol == "codex"
    }
    override suspend fun accountId(setting: ProviderSetting.OpenAI): String? = withContext(Dispatchers.IO) {
        session(setting.id)?.accountId?.takeIf(String::isNotBlank)
    }
    suspend fun signOut(id: Uuid) = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            secrets.setSubscriptionPending(id, null)
            val previous = session(id)
            if (previous?.type == SubscriptionAccountType.CHATGPT && previous.protocol == "direct")
                save(id, previous.withoutCredentials())
            else secrets.setSubscriptionSession(id, null)
        }
    }
    override suspend fun accessToken(setting: ProviderSetting.OpenAI): String = resolveToken(setting, false)
    override suspend fun refreshAccessToken(setting: ProviderSetting.OpenAI): String = resolveToken(setting, true)

    private suspend fun resolveToken(setting: ProviderSetting.OpenAI, force: Boolean): String = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            val current = session(setting.id)?.takeIf { it.type == setting.subscriptionAccount && it.accessToken.isNotBlank() }
                ?: error("Sign in to ${setting.subscriptionAccount?.displayName} in provider settings first.")
            if (!force && current.expiresAt > now() + 60) return@withLock current.accessToken
            check(current.refreshToken.isNotBlank()) { "Your subscription session expired. Sign in again." }
            // Rotating refresh tokens must be persisted even if the calling chat/UI is cancelled.
            withContext(NonCancellable) {
                withTimeout(30_000) {
                    val transport = http(setting)
                    val fields = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken,
                        "client_id" to current.clientId)
                    val endpoint = when {
                        current.type == SubscriptionAccountType.GROK -> GROK_TOKEN
                        current.protocol == "codex" -> CodexOAuth.TOKEN
                        else -> discovery(transport).token.also { fields["resource"] = ChatGptOAuth.RESOURCE }
                    }
                    val (status, body) = post(transport, endpoint, fields, jsonBody = current.protocol == "codex")
                    if (status !in 200..299) {
                        if (body.text("error") in TERMINAL_REFRESH_ERRORS) {
                            save(setting.id, current.withoutCredentials())
                            error("Your subscription session expired. Sign in again in provider settings.")
                        }
                        throw providerHttpError(status, body.toString(), "Could not refresh your subscription session.")
                    }
                    val refreshed = tokenSession(body, current.type, current.clientId, current)
                    if (current.type == SubscriptionAccountType.CHATGPT && current.protocol == "direct") {
                        check("chatgpt.tokens.use.direct" in refreshed.scope.split(' ')) { "ChatGPT plan usage is no longer enabled. Sign in again." }
                    }
                    save(setting.id, refreshed)
                    refreshed.accessToken
                }
            }
        }
    }

    suspend fun signIn(setting: ProviderSetting.OpenAI, deviceCode: Boolean = false,
        onPrompt: suspend (SubscriptionLoginPrompt) -> Unit) = withContext(Dispatchers.IO) {
        loginLock.withLock {
            val result = withTimeout(if (deviceCode) 15 * 60_000L else 5 * 60_000L) {
                when (requireNotNull(setting.subscriptionAccount)) {
                    SubscriptionAccountType.CHATGPT -> chatGptLogin(setting, deviceCode, onPrompt)
                    SubscriptionAccountType.GROK -> grokLogin(http(setting), onPrompt)
                }
            }
            sessionLock.withLock {
                save(setting.id, result)
                secrets.setSubscriptionPending(setting.id, null)
            }
        }
    }

    private suspend fun discovery(transport: PlatformHttpClient) = withTimeout(15_000) {
        val response = transport.execute(PlatformHttpRequest("GET", "${ChatGptOAuth.ISSUER}/.well-known/openid-configuration"))
        check(response.statusCode in 200..299) { "ChatGPT sign-in is temporarily unavailable. Try again shortly." }
        ChatGptOAuth.configuration(JsonInstant.parseToJsonElement(response.body.decodeToString()).jsonObject)
    }

    private suspend fun chatGptLogin(setting: ProviderSetting.OpenAI, device: Boolean,
        onPrompt: suspend (SubscriptionLoginPrompt) -> Unit): SubscriptionSession {
        val transport = http(setting)
        // Verify app-side connectivity before sending the user away to the browser.
        val configuration = discovery(transport)
        val checkpoint = pending(setting.id) ?: if (device) {
            deviceAuthorization(transport, onPrompt)
        } else browserAuthorization(onPrompt)
        savePending(setting.id, checkpoint)
        val body = checkpoint.tokenResponse?.let { JsonInstant.parseToJsonElement(it).jsonObject } ?: run {
            val fields = mapOf("grant_type" to "authorization_code", "client_id" to CodexOAuth.CLIENT_ID,
                "code" to checkpoint.code, "code_verifier" to checkpoint.verifier, "redirect_uri" to checkpoint.redirect)
            val (status, response) = post(transport, CodexOAuth.TOKEN, fields)
            if (status !in 200..299) {
                if (status in 400..499) secrets.setSubscriptionPending(setting.id, null)
                if (response.text("error") == "invalid_grant") error("This sign-in code expired or was already used. Continue again to start a fresh sign-in.")
                throw providerHttpError(status, response.toString())
            }
            // Never reuse a consumed authorization code if identity-key retrieval fails.
            savePending(setting.id, checkpoint.copy(tokenResponse = response.toString()))
            response
        }
        val token = body.text("id_token") ?: error("ChatGPT did not return an identity token. Please sign in again.")
        val jwks = transport.execute(PlatformHttpRequest("GET", configuration.jwks))
        check(jwks.statusCode in 200..299) { "Could not verify ChatGPT identity. Retry sign-in to finish this connection." }
        val identity = AndroidSubscriptionIdentity.verify(token, jwks.body.decodeToString(), CodexOAuth.CLIENT_ID, checkpoint.nonce)
        val claims = CodexOAuth.accountClaims(token)
        val account = claims.text("chatgpt_account_id") ?: error("ChatGPT did not return a Codex account. Check your account access.")
        return tokenSession(body, SubscriptionAccountType.CHATGPT, CodexOAuth.CLIENT_ID).copy(
            subject = identity.first, email = identity.second, accountId = account,
            plan = claims.text("chatgpt_plan_type").orEmpty(), protocol = "codex")
    }

    private suspend fun browserAuthorization(onPrompt: suspend (SubscriptionLoginPrompt) -> Unit): PendingSubscriptionLogin {
        val state = AndroidSubscriptionIdentity.randomValue()
        val nonce = AndroidSubscriptionIdentity.randomValue()
        val verifier = AndroidSubscriptionIdentity.randomValue()
        val callback = CompletableDeferred<Map<String, String>>()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 1455) {
            routing {
                get("/auth/callback") {
                    call.response.headers.append("Cache-Control", "no-store")
                    call.response.headers.append("Referrer-Policy", "no-referrer")
                    call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; frame-ancestors 'none'")
                    val parameters = call.request.queryParameters.entries().associate { it.key to it.value }
                    val parsed = runCatching { ChatGptOAuth.callback(parameters, state) }
                    if (parsed.isFailure || parsed.getOrNull() == null) {
                        call.respondText("Invalid sign-in callback. Return to LastChat to try again.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                        return@get
                    }
                    val values = requireNotNull(parsed.getOrNull())
                    val declined = values["error"] != null
                    call.respondText("""<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>LastChat</title><style>body{font:17px system-ui;background:#181818;color:#eee;max-width:32rem;margin:15vh auto;padding:24px}h1{font-size:26px}</style></head><body><h1>${if (declined) "Sign-in was declined" else "Return to LastChat"}</h1><p>${if (declined) "You can try again from the app." else "LastChat is checking your connection. You can close this tab."}</p><script nonce="$nonce">history.replaceState(null,'','/auth/complete')</script></body></html>""", ContentType.Text.Html)
                    callback.complete(values)
                }
            }
        }
        try {
            try { server.start(wait = false) } catch (e: Exception) {
                throw IllegalStateException("The sign-in callback port is in use. Use a sign-in code instead.", e)
            }
            onPrompt(SubscriptionLoginPrompt(CodexOAuth.authorizationUrl(state, nonce, verifier)))
            val result = callback.await()
            check(result["error"] == null) { "ChatGPT sign-in was declined. Please try again when ready." }
            val code = result["code"]?.takeIf { it.isNotBlank() } ?: error("ChatGPT returned no authorization code.")
            return PendingSubscriptionLogin(code, verifier, nonce, CodexOAuth.REDIRECT, now() + 5 * 60)
        } finally { withContext(NonCancellable) { server.stop(0, 500) } }
    }

    private suspend fun deviceAuthorization(transport: PlatformHttpClient,
        onPrompt: suspend (SubscriptionLoginPrompt) -> Unit): PendingSubscriptionLogin {
        val (status, device) = post(transport, "https://auth.openai.com/api/accounts/deviceauth/usercode",
            mapOf("client_id" to CodexOAuth.CLIENT_ID), jsonBody = true)
        check(status in 200..299) { "ChatGPT device sign-in could not start (HTTP $status). Use browser sign-in instead." }
        val id = device.text("device_auth_id") ?: error("ChatGPT returned no device authorization.")
        val code = device.text("user_code") ?: device.text("usercode") ?: error("ChatGPT returned no sign-in code.")
        val interval = device.text("interval")?.toLongOrNull()?.coerceIn(1, 60) ?: 5
        onPrompt(SubscriptionLoginPrompt(CodexOAuth.DEVICE_VERIFY, code))
        while (true) {
            delay(interval * 1000)
            val (pollStatus, body) = post(transport, "https://auth.openai.com/api/accounts/deviceauth/token",
                mapOf("device_auth_id" to id, "user_code" to code), jsonBody = true)
            if (pollStatus in 200..299) {
                val verifier = body.text("code_verifier") ?: error("ChatGPT returned no PKCE verifier.")
                check(AndroidSubscriptionIdentity.challenge(verifier) == body.text("code_challenge")) { "ChatGPT returned an invalid PKCE challenge." }
                return PendingSubscriptionLogin(body.text("authorization_code") ?: error("ChatGPT returned no authorization code."),
                    verifier, redirect = CodexOAuth.DEVICE_REDIRECT, expiresAt = now() + 5 * 60)
            }
            check(pollStatus == 403 || pollStatus == 404) { "ChatGPT device sign-in failed (HTTP $pollStatus). Start again." }
        }
    }

    private suspend fun grokLogin(transport: PlatformHttpClient,
        onPrompt: suspend (SubscriptionLoginPrompt) -> Unit): SubscriptionSession {
        val (status, body) = post(transport, "https://auth.x.ai/oauth2/device/code", mapOf(
            "client_id" to GROK_CLIENT, "scope" to "openid profile email offline_access grok-cli:access api:access"))
        check(status in 200..299) { "Grok sign-in could not start (HTTP $status). Please try again later." }
        val deviceCode = body.text("device_code") ?: error("Grok returned no device code.")
        val code = body.text("user_code") ?: error("Grok returned no sign-in code.")
        val url = body.text("verification_uri_complete") ?: body.text("verification_uri") ?: error("Grok returned no sign-in address.")
        check(url.startsWith("https://") && url.urlHostOrNull() in setOf("auth.x.ai", "accounts.x.ai", "grok.com")) { "Grok returned an unexpected sign-in address." }
        onPrompt(SubscriptionLoginPrompt(url, code))
        var interval = body.text("interval")?.toLongOrNull()?.coerceIn(1, 60) ?: 5
        val expiry = now() + (body.text("expires_in")?.toLongOrNull()?.coerceIn(1, 900) ?: 300)
        while (now() < expiry) {
            delay(interval * 1000)
            val (tokenStatus, token) = post(transport, GROK_TOKEN, mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to deviceCode, "client_id" to GROK_CLIENT))
            if (tokenStatus in 200..299) return tokenSession(token, SubscriptionAccountType.GROK, GROK_CLIENT)
            when (token.text("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> error("Grok sign-in was declined.")
                "expired_token" -> error("The Grok sign-in code expired. Please try again.")
                else -> throw providerHttpError(tokenStatus, token.toString())
            }
        }
        error("The Grok sign-in code expired. Please try again.")
    }

    private fun tokenSession(body: JsonObject, type: SubscriptionAccountType, clientId: String,
        previous: SubscriptionSession? = null): SubscriptionSession {
        val access = body.text("access_token")?.takeIf { it.isNotBlank() } ?: error("The provider did not return an access token. Please sign in again.")
        check(body.text("token_type")?.equals("Bearer", true) != false) { "Unsupported sign-in token type." }
        val ttl = body.text("expires_in")?.toLongOrNull()?.takeIf { it > 0 } ?: 3600
        return SubscriptionSession(type, clientId, access, body.text("refresh_token") ?: previous?.refreshToken.orEmpty(),
            body.text("id_token") ?: previous?.idToken.orEmpty(), previous?.subject.orEmpty(), previous?.email.orEmpty(),
            body.text("scope") ?: previous?.scope.orEmpty(), now() + ttl, previous?.protocol ?: "direct",
            previous?.accountId.orEmpty(), previous?.plan.orEmpty())
    }
    private fun SubscriptionSession.withoutCredentials() = copy(accessToken = "", refreshToken = "", idToken = "", scope = "", expiresAt = 0)

    private suspend fun post(transport: PlatformHttpClient, url: String, fields: Map<String, String>,
        jsonBody: Boolean = false): Pair<Int, JsonObject> = withTimeout(30_000) {
        val contentType = if (jsonBody) "application/json" else "application/x-www-form-urlencoded"
        val encoded = if (jsonBody) buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
            else fields.entries.joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }
        val response = transport.execute(PlatformHttpRequest("POST", url,
            headers = mapOf("Content-Type" to contentType, "Accept" to "application/json"),
            body = encoded.encodeToByteArray(), mediaType = contentType))
        val body = runCatching { JsonInstant.parseToJsonElement(response.body.decodeToString()) as? JsonObject }.getOrNull()
            ?: if (response.statusCode !in 200..299) throw providerHttpError(response.statusCode, response.body.decodeToString())
            else error("The provider returned an invalid sign-in response.")
        response.statusCode to body
    }
    private fun now() = Clock.System.now().epochSeconds
    companion object {
        private const val GROK_TOKEN = "https://auth.x.ai/oauth2/token"
        private const val GROK_CLIENT = "b1a00492-073a-47ea-816f-4c329264a828"
        private val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    }
}

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
