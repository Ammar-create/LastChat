package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
