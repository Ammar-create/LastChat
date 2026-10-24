package me.rerere.rikkahub.data.ai.subscription

import java.net.URI
import me.rerere.common.http.urlDecode
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptOAuthTest {
    private val config = ChatGptOAuth.Configuration("https://auth.openai.com/api/accounts/authorize",
        "https://auth.openai.com/api/accounts/oauth/token", "https://auth.openai.com/.well-known/jwks.json")
    private fun request(previous: SubscriptionSession? = null) = ChatGptOAuth.authorizationUrl(config,
        "urn:uuid:00000000-0000-4000-8000-000000000000", "http://127.0.0.1:54321/auth/callback",
        "fresh-state", "fresh-nonce", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", previous)
    private fun query(url: String) = URI(url).rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        parts[0].urlDecode() to parts[1].urlDecode()
    }

    @Test fun initialRequestMatchesOpenAiDevkitIncludingUrnHostIdAndPkce() {
        val fields = query(request())
        assertEquals("dynamic_agent_client", fields["client_id"])
        assertEquals("LastChat", fields["agent_name_hint"])
        assertEquals("urn:uuid:00000000-0000-4000-8000-000000000000", fields["ext_agent_host_id"])
        assertEquals("http://127.0.0.1:54321/auth/callback", fields["redirect_uri"])
        assertEquals(ChatGptOAuth.SCOPES, fields["scope"])
        assertEquals(ChatGptOAuth.RESOURCE, fields["resource"])
        assertEquals("fresh-state", fields["state"])
        assertEquals("fresh-nonce", fields["nonce"])
        assertEquals("S256", fields["code_challenge_method"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", fields["code_challenge"])
    }

    @Test fun reconnectReusesRegistrationAndKeepsTokensOutOfBrowserUrl() {
        val previous = SubscriptionSession(SubscriptionAccountType.CHATGPT, "oaiapp_saved", "secret-access",
            idToken = "secret-identity", email = "a+b@example.com", expiresAt = 0)
        val url = request(previous)
        val fields = query(url)
        assertEquals("oaiapp_saved", fields["client_id"])
        assertEquals("a+b@example.com", fields["login_hint"])
        assertFalse(fields.containsKey("agent_name_hint"))
        assertFalse(url.contains("secret"))
    }

    @Test fun rejectsUntrustedDiscoveryAndAmbiguousCallbacks() {
        for (endpoint in listOf("http://auth.openai.com/token", "https://auth.openai.com.evil.example/token",
            "https://evil.example/token", "https://evil@auth.openai.com/token")) {
            val body = JsonInstant.parseToJsonElement("""{"issuer":"https://auth.openai.com","authorization_endpoint":"$endpoint","token_endpoint":"${config.token}","jwks_uri":"${config.jwks}"}""").jsonObject
            assertTrue(runCatching { ChatGptOAuth.configuration(body) }.isFailure)
        }
        assertNull(ChatGptOAuth.callback(mapOf("state" to listOf("other")), "state"))
        assertNull(ChatGptOAuth.callback(mapOf("state" to listOf("state", "state")), "state"))
        assertTrue(runCatching { ChatGptOAuth.callback(mapOf("state" to listOf("state"), "code" to listOf("a", "b")), "state") }.isFailure)
        assertEquals("code", ChatGptOAuth.callback(mapOf("state" to listOf("state"), "code" to listOf("code")), "state")?.get("code"))
    }
}
