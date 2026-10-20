package me.rerere.rikkahub.data.ai.subscription

import kotlinx.serialization.json.*
import me.rerere.common.http.urlEncode

/** Matches RikkaHub 3.1.0 and openai/codex's public native OAuth contract. */
internal object CodexOAuth {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN = "https://auth.openai.com/oauth/token"
    const val REDIRECT = "http://127.0.0.1:1455/auth/callback"
    const val DEVICE_REDIRECT = "https://auth.openai.com/deviceauth/callback"
    const val DEVICE_VERIFY = "https://auth.openai.com/codex/device"

    fun authorizationUrl(state: String, nonce: String, verifier: String) =
        "https://auth.openai.com/oauth/authorize?" + mapOf(
            "client_id" to CLIENT_ID, "response_type" to "code", "redirect_uri" to REDIRECT,
            "scope" to "openid profile email offline_access", "state" to state, "nonce" to nonce,
            "code_challenge_method" to "S256", "code_challenge" to AndroidSubscriptionIdentity.challenge(verifier),
            "id_token_add_organizations" to "true", "codex_cli_simplified_flow" to "true", "originator" to "codex_cli_rs",
        ).entries.joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }

    fun accountClaims(idToken: String): JsonObject {
        val parts = idToken.split('.')
        require(parts.size == 3)
        val base64 = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT_OPTIONAL)
        val claims = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(base64.decode(parts[1]).decodeToString()).jsonObject
        return claims["https://api.openai.com/auth"] as? JsonObject ?: buildJsonObject { }
    }
}
