package me.rerere.rikkahub.data.ai.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.urlEncode
import me.rerere.common.http.urlPartsOrNull

/** Request contract shared by discovery, the browser transaction and its regression tests. */
internal object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    data class Configuration(val authorize: String, val token: String, val jwks: String)

    fun configuration(body: JsonObject): Configuration {
        require(body["issuer"]?.jsonPrimitive?.contentOrNull == ISSUER) { "Could not verify ChatGPT sign-in configuration." }
        fun endpoint(name: String): String {
            val value = body[name]?.jsonPrimitive?.contentOrNull ?: error("ChatGPT sign-in configuration is incomplete.")
            val parts = value.urlPartsOrNull()
            val authority = value.substringAfter("://").substringBefore('/')
            require(parts?.scheme == "https" && parts.host == "auth.openai.com" &&
                authority in setOf("auth.openai.com", "auth.openai.com:443") &&
                value.none { it.isWhitespace() || it in "?#\\" }) {
                "ChatGPT returned an unexpected sign-in endpoint."
            }
            return value
        }
        return Configuration(endpoint("authorization_endpoint"), endpoint("token_endpoint"), endpoint("jwks_uri"))
    }

    fun authorizationUrl(config: Configuration, hostId: String, redirect: String, state: String,
        nonce: String, verifier: String, previous: SubscriptionSession?): String {
        // A bare UUID is rejected by OpenAI before the user can even sign in.
        require(hostId.startsWith("urn:uuid:")) { "Invalid ChatGPT installation ID." }
        val fields = linkedMapOf(
            "client_id" to (previous?.clientId ?: DYNAMIC_CLIENT),
            "ext_agent_host_id" to hostId, "response_type" to "code", "redirect_uri" to redirect,
            "scope" to SCOPES, "resource" to RESOURCE, "state" to state, "nonce" to nonce,
            "code_challenge_method" to "S256", "code_challenge" to AndroidSubscriptionIdentity.challenge(verifier),
        )
        if (previous == null) fields["agent_name_hint"] = "LastChat"
        // As in OpenAI's DevKit, keep ID tokens out of browser URLs/history.
        else {
            if (previous.email.isNotBlank()) fields["login_hint"] = previous.email
            if ("chatgpt.tokens.use.direct" !in previous.scope.split(' ')) fields["prompt"] = "consent"
        }
        return config.authorize + "?" + fields.entries.joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }
    }

    fun callback(parameters: Map<String, List<String>>, state: String): Map<String, String>? {
        if (parameters["state"] != listOf(state)) return null
        require(parameters.values.all { it.size == 1 }) { "ChatGPT returned an ambiguous sign-in callback. Please try again." }
        return parameters.mapValues { it.value.single() }
    }
}
