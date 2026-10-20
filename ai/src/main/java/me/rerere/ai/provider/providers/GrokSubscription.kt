package me.rerere.ai.provider.providers

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.json
import kotlin.time.Instant

/** Current Grok account catalog: CLI overrides public API entries with the same model ID. */
internal fun parseGrokSubscriptionModels(catalogs: List<JsonObject>): List<Model> {
    val models = linkedMapOf<String, Model>()
    catalogs.forEach { catalog ->
        (catalog["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach row@{ row ->
            val meta = row["_meta"] as? JsonObject
            fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun metaText(key: String) = (meta?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val id = text("model") ?: text("modelId") ?: text("id") ?: metaText("model") ?: metaText("modelId") ?: return@row
            val hidden = (row["hidden"] ?: meta?.get("hidden"))?.jsonPrimitive?.booleanOrNull == true
            if (hidden || Regex("imagine|tts|embed|video", RegexOption.IGNORE_CASE).containsMatchIn(id) || id in models) return@row
            val efforts = (row["reasoningEfforts"] ?: row["reasoning_efforts"] ?: meta?.get("reasoningEfforts")) as? JsonArray
            val context = listOf(row["contextWindow"], row["context_window"], meta?.get("contextWindow"), meta?.get("totalContextTokens"))
                .firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } }
            models[id] = Model(modelId = id, displayName = text("name") ?: text("displayName") ?: text("display_name") ?: metaText("displayName") ?: id,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE), contextWindowTokens = context,
                abilities = if (efforts.isNullOrEmpty()) listOf(ModelAbility.TOOL) else listOf(ModelAbility.TOOL, ModelAbility.REASONING))
        }
    }
    return models.values.toList()
}

internal fun parseGrokSubscriptionUsage(credits: JsonObject, billing: JsonObject? = null): ProviderUsage? {
    fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun JsonObject.instant(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val config = credits["config"] as? JsonObject
    if (config?.get("isUnifiedBillingUser")?.jsonPrimitive?.booleanOrNull != true) {
        val period = config?.get("currentPeriod") as? JsonObject
        val type = period?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val percent = config?.number("creditUsagePercent")
        val start = period?.instant("start")
        val end = period?.instant("end")
        if (type.contains("WEEK", true) && percent != null && percent >= 0 && start != null && end != null && end > start)
            return ProviderUsage(listOf(ProviderUsageWindow("Weekly", percent, end)), percent >= 100)
    }
    val monthly = billing?.get("config") as? JsonObject ?: return null
    val start = monthly.instant("billingPeriodStart") ?: return null
    val end = monthly.instant("billingPeriodEnd") ?: return null
    val limit = (monthly["monthlyLimit"] as? JsonObject)?.number("val") ?: return null
    val used = (monthly["used"] as? JsonObject)?.number("val") ?: return null
    if (end <= start || limit <= 0 || used < 0) return null
    return ProviderUsage(listOf(ProviderUsageWindow("Monthly", used / limit * 100, end)), used >= limit)
}
