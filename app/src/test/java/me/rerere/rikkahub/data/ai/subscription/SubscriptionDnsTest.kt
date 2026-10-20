package me.rerere.rikkahub.data.ai.subscription

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDnsTest {
    private val address = listOf(InetAddress.getByName("192.0.2.1"))
    @Test fun workingSystemDnsNeverContactsPublicFallback() {
        var fallbackCalls = 0
        val resolver = SubscriptionDns(Dns { address }, Dns { fallbackCalls++; error("Must preserve working DNS") })
        assertEquals(address, resolver.lookup("auth.openai.com"))
        assertEquals(0, fallbackCalls)
    }
    @Test fun failureUsesFallbackOnlyForAccountServiceHosts() {
        val fallbacks = mutableListOf<String>()
        val resolver = SubscriptionDns(Dns { throw UnknownHostException("blocked") }, Dns { fallbacks += it; address })
        assertEquals(address, resolver.lookup("auth.openai.com"))
        assertTrue(runCatching { resolver.lookup("private.corporate.example") }.exceptionOrNull() is UnknownHostException)
        assertTrue(runCatching { resolver.lookup("localhost") }.exceptionOrNull() is UnknownHostException)
        assertEquals(listOf("auth.openai.com"), fallbacks)
    }
}
