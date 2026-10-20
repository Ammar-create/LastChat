package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant

/** Percentages are provider-reported quota usage, independent of local token statistics. */
data class ProviderUsage(val windows: List<ProviderUsageWindow>, val limitReached: Boolean = false)
data class ProviderUsageWindow(val label: String, val usedPercent: Double, val resetsAt: Instant? = null)

fun parseOpenCodeUsage(body: String): ProviderUsage {
    val root = json.parseToJsonElement(body).jsonObject
    val usage = root["usage"] as? JsonObject ?: error("OpenCode returned an unrecognized usage response. Open Console to check usage.")
    val labels = mapOf("rolling" to "5-hour window", "weekly" to "Weekly", "monthly" to "Monthly")
    val windows = labels.mapNotNull { (key, label) ->
        val window = usage[key] as? JsonObject ?: return@mapNotNull null
        val percent = (window["percent"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        val reset = (window["resetsAt"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ProviderUsageWindow(label, percent, reset)
    }
    check(windows.isNotEmpty()) { "OpenCode returned no usage windows. Open Console to check usage." }
    return ProviderUsage(windows, windows.any { it.usedPercent >= 100 } || usage.values.any {
        (it as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "rate-limited"
    })
}

fun parseCodexUsage(body: String): ProviderUsage {
    val root = json.parseToJsonElement(body).jsonObject
    val rateLimit = root["rate_limit"] as? JsonObject ?: error("ChatGPT returned no usage information.")
    val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
        val window = rateLimit[key] as? JsonObject ?: return@mapNotNull null
        val percent = (window["used_percent"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        val seconds = (window["limit_window_seconds"] as? JsonPrimitive)?.longOrNull
        val label = when (seconds) {
            18000L -> "5-hour window"
            604800L -> "Weekly"
            null -> if (key == "primary_window") "Primary window" else "Secondary window"
            else -> if (seconds >= 86400) "${seconds / 86400}-day window" else "${seconds / 3600}-hour window"
        }
        val reset = (window["reset_at"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let(Instant::fromEpochSeconds)
            ?: (window["reset_after_seconds"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                ?.let { Instant.fromEpochSeconds(Clock.System.now().epochSeconds + it) }
        ProviderUsageWindow(label, percent, reset)
    }
    return ProviderUsage(windows, (rateLimit["limit_reached"] as? JsonPrimitive)?.booleanOrNull == true)
}
