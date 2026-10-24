package me.rerere.rikkahub.data.ai.subscription

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** Android/JVM cryptography boundary for PKCE and OpenAI's RS256 OIDC identity tokens. */
internal object AndroidSubscriptionIdentity {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    fun randomValue(): String = base64.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = base64.encode(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))

    fun verify(token: String, jwks: String, clientId: String, nonce: String?): Pair<String, String> {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ChatGPT identity token." }
        val header = JsonInstant.parseToJsonElement(base64.decode(parts[0]).decodeToString()).jsonObject
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        require(header.text("alg") == "RS256") { "Unsupported ChatGPT identity signature." }
        val keys = JsonInstant.parseToJsonElement(jwks).jsonObject["keys"] as? JsonArray ?: error("Identity keys unavailable.")
        val jwk = keys.mapNotNull { it as? JsonObject }.firstOrNull {
            it.text("kid") == header.text("kid") && it.text("kty") == "RSA" &&
                (it.text("use") == null || it.text("use") == "sig")
        } ?: error("ChatGPT identity signing key not found.")
        val modulus = BigInteger(1, base64.decode(requireNotNull(jwk.text("n"))))
        val exponent = BigInteger(1, base64.decode(requireNotNull(jwk.text("e"))))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".encodeToByteArray())
        }
        require(signature.verify(base64.decode(parts[2]))) { "ChatGPT identity signature was invalid." }
        val claims = JsonInstant.parseToJsonElement(base64.decode(parts[1]).decodeToString()).jsonObject
        require(claims.text("iss") == "https://auth.openai.com") { "Unexpected ChatGPT identity issuer." }
        val audience = claims["aud"]
        require((audience as? JsonPrimitive)?.contentOrNull == clientId ||
            (audience as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == clientId } == true) {
            "ChatGPT identity belongs to a different client."
        }
        val authorizedParty = claims.text("azp")
        require((authorizedParty == null || authorizedParty == clientId) &&
            (audience !is JsonArray || audience.size <= 1 || authorizedParty == clientId)) {
            "ChatGPT identity belongs to a different authorized client."
        }
        require(nonce == null || claims.text("nonce") == nonce) { "ChatGPT sign-in did not match this attempt." }
        require(((claims["exp"] as? JsonPrimitive)?.longOrNull ?: 0) > Clock.System.now().epochSeconds) {
            "ChatGPT identity token expired. Please sign in again."
        }
        val subject = claims.text("sub")?.takeIf { it.isNotBlank() } ?: error("ChatGPT returned no account identity.")
        return subject to claims.text("email").orEmpty()
    }
}
