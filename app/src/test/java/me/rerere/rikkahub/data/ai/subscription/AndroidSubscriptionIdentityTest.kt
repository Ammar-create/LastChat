package me.rerere.rikkahub.data.ai.subscription

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.time.Clock

class AndroidSubscriptionIdentityTest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwks: String get() {
        val key = pair.public as RSAPublicKey
        return """{"keys":[{"kid":"test","kty":"RSA","n":"${base64.encode(key.modulus.toByteArray())}","e":"${base64.encode(key.publicExponent.toByteArray())}"}]}"""
    }
    private fun token(issuer: String = "https://auth.openai.com", audience: String = "client", nonce: String = "nonce",
        expiry: Long = Clock.System.now().epochSeconds + 60): String {
        val header = base64.encode("""{"alg":"RS256","kid":"test"}""".encodeToByteArray())
        val payload = base64.encode("""{"iss":"$issuer","aud":"$audience","nonce":"$nonce","exp":$expiry,"sub":"account","email":"me@example.com"}""".encodeToByteArray())
        val input = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(input.encodeToByteArray()) }.sign()
        return "$input.${base64.encode(signature)}"
    }
    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            AndroidSubscriptionIdentity.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun verifiesSignatureAndIdentity() {
        assertEquals("account" to "me@example.com", AndroidSubscriptionIdentity.verify(token(), jwks, "client", "nonce"))
    }
    @Test fun rejectsWrongIssuerAudienceNonceExpiryAndSignature() {
        val wrongSignature = token().substringBeforeLast('.') + "." + base64.encode(ByteArray(256))
        for (invalid in listOf(token(issuer = "https://evil.example"), token(audience = "other"),
            token(nonce = "other"), token(expiry = 1), wrongSignature)) {
            assertTrue(runCatching { AndroidSubscriptionIdentity.verify(invalid, jwks, "client", "nonce") }.isFailure)
        }
    }
}
