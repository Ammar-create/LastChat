package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Three-way merge for callers that hand back a whole edited snapshot: only what changed between
 * [base] (the snapshot the edit started from) and [edited] is applied on top of [latest], so a
 * change saved meanwhile by another screen or a quick second tap is kept. Objects merge per key;
 * lists and scalars are replaced as a whole when edited.
 */
fun mergeSettingsEdit(base: Settings, edited: Settings, latest: Settings): Settings {
    if (edited == base) return latest
    if (latest == base) return edited
    val json = JsonInstant
    val b = json.encodeToJsonElement(Settings.serializer(), base)
    val e = json.encodeToJsonElement(Settings.serializer(), edited)
    val l = json.encodeToJsonElement(Settings.serializer(), latest)
    val merged = mergeJson(b, e, l) ?: return latest
    return json.decodeFromJsonElement(Settings.serializer(), merged)
        .copy(init = latest.init, storeRevision = latest.storeRevision)
}

internal fun mergeJson(base: JsonElement?, edited: JsonElement?, latest: JsonElement?): JsonElement? {
    if (edited == base) return latest
    if (base is JsonArray && edited is JsonArray && latest is JsonArray) {
        mergeIdentifiedArray(base, edited, latest)?.let { return it }
    }
    if (base is JsonObject && edited is JsonObject && latest is JsonObject) {
        val keys = base.keys + edited.keys + latest.keys
        val out = LinkedHashMap<String, JsonElement>()
        for (k in keys) {
            mergeJson(base[k], edited[k], latest[k])?.let { out[k] = it }
        }
        return JsonObject(out)
    }
    return edited
}

/** Field edits and ordering changes to ID-bearing lists commute instead of replacing the list. */
private fun mergeIdentifiedArray(base: JsonArray, edited: JsonArray, latest: JsonArray): JsonArray? {
    fun identify(array: JsonArray): LinkedHashMap<String, JsonElement>? {
        val result = linkedMapOf<String, JsonElement>()
        for (element in array) {
            val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: return null
            if (result.put(id, element) != null) return null
        }
        return result
    }
    val before = identify(base) ?: return null
    val after = identify(edited) ?: return null
    val current = identify(latest) ?: return null
    val removed = before.keys - after.keys
    val result = linkedMapOf<String, JsonElement>()
    for ((id, value) in current) {
        if (id !in removed) result[id] = if (id in after) mergeJson(before[id], after[id], value) ?: value else value
    }
    // An item deleted meanwhile must stay deleted; only genuinely new IDs may be inserted.
    for ((id, value) in after) if (id !in before && id !in result) result[id] = value
    val common = before.keys.intersect(after.keys)
    if ((after.keys - before.keys).isNotEmpty() ||
        before.keys.filter { it in common } != after.keys.filter { it in common }) {
        val requested = after.keys.filter { it in result }.iterator()
        val included = after.keys.intersect(result.keys)
        return JsonArray(result.keys.map { id -> result[if (id in included) requested.next() else id]!! })
    }
    return JsonArray(result.values.toList())
}

internal fun mergeProviderEdit(
    base: me.rerere.ai.provider.ProviderSetting,
    edited: me.rerere.ai.provider.ProviderSetting,
    latest: me.rerere.ai.provider.ProviderSetting,
): me.rerere.ai.provider.ProviderSetting {
    val serializer = me.rerere.ai.provider.ProviderSetting.serializer()
    val b = JsonInstant.encodeToJsonElement(serializer, base)
    val e = JsonInstant.encodeToJsonElement(serializer, edited)
    val l = JsonInstant.encodeToJsonElement(serializer, latest)
    if ((b as? JsonObject)?.get("type") != (e as? JsonObject)?.get("type")) return edited
    return JsonInstant.decodeFromJsonElement(serializer, mergeJson(b, e, l) ?: l)
}

internal fun mergeTtsProviderEdit(
    base: me.rerere.tts.provider.TTSProviderSetting,
    edited: me.rerere.tts.provider.TTSProviderSetting,
    latest: me.rerere.tts.provider.TTSProviderSetting,
): me.rerere.tts.provider.TTSProviderSetting {
    val serializer = me.rerere.tts.provider.TTSProviderSetting.serializer()
    val b = JsonInstant.encodeToJsonElement(serializer, base)
    val e = JsonInstant.encodeToJsonElement(serializer, edited)
    val l = JsonInstant.encodeToJsonElement(serializer, latest)
    return JsonInstant.decodeFromJsonElement(serializer, mergeJson(b, e, l) ?: l)
}

internal fun mergeAssistantEdit(
    base: me.rerere.rikkahub.data.model.Assistant,
    edited: me.rerere.rikkahub.data.model.Assistant,
    latest: me.rerere.rikkahub.data.model.Assistant,
): me.rerere.rikkahub.data.model.Assistant {
    val serializer = me.rerere.rikkahub.data.model.Assistant.serializer()
    val b = JsonInstant.encodeToJsonElement(serializer, base)
    val e = JsonInstant.encodeToJsonElement(serializer, edited)
    val l = JsonInstant.encodeToJsonElement(serializer, latest)
    return JsonInstant.decodeFromJsonElement(serializer, mergeJson(b, e, l) ?: l)
}
