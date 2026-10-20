package me.rerere.rikkahub.data.ai.subscription

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Explicit opt-in, used only for account service hosts after the system resolver fails. */
internal class SubscriptionDns(
    private val system: Dns,
    private val fallback: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val failure = try {
            val addresses = system.lookup(hostname)
            if (addresses.isNotEmpty()) return addresses
            UnknownHostException("The system resolver returned no addresses.")
        } catch (e: UnknownHostException) { e }
        if (hostname.lowercase() !in SERVICE_HOSTS) throw failure
        return try {
            fallback.lookup(hostname).takeIf { it.isNotEmpty() } ?: throw failure
        } catch (e: UnknownHostException) {
            if (e !== failure) e.addSuppressed(failure)
            throw e
        }
    }

    companion object {
        private val SERVICE_HOSTS = setOf("auth.openai.com", "api.openai.com", "chatgpt.com",
            "auth.x.ai", "api.x.ai", "accounts.x.ai", "cli-chat-proxy.grok.com", "grok.com")

        fun create(): Dns {
            val client = OkHttpClient.Builder().callTimeout(4.seconds.toJavaDuration())
                .connectTimeout(3.seconds.toJavaDuration()).readTimeout(3.seconds.toJavaDuration()).build()
            val secureDns = DnsOverHttps.Builder().client(client)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                .build()
            return SubscriptionDns(Dns.SYSTEM, secureDns)
        }
    }
}
