package me.rerere.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class ModelToolDefaultsTest {
    @Test fun newChatModelsSupportToolsWhileNonChatModelsDoNot() {
        assertTrue(ModelAbility.TOOL in Model(modelId = "unknown-new-model").abilities)
        for (type in listOf(ModelType.EMBEDDING, ModelType.IMAGE, ModelType.STT)) {
            assertTrue(Model(type = type).abilities.isEmpty())
        }
    }

    @Test fun explicitOptOutSurvivesPersistence() {
        val model = Model(modelId = "custom", abilities = emptyList())
        assertTrue(json.decodeFromString<Model>(json.encodeToString(model)).abilities.isEmpty())
    }
}
