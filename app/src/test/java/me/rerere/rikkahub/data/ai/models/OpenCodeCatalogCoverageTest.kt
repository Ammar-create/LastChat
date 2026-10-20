package me.rerere.rikkahub.data.ai.models

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.*
import org.junit.Assert.*
import org.junit.Test

class OpenCodeCatalogCoverageTest {
    @Test fun everyLiveOpenCodeModelHasMetadataIncludingCustomProviderIds() {
        val catalog = sequenceOf(File("../catalog/lastchat_catalog.json"), File("catalog/lastchat_catalog.json")).first { it.exists() }
        val snapshot = ModelCatalogParser.parse(catalog.readText())
        val resolver = ModelMetadataResolver { snapshot }
        for (tier in listOf("go", "zen")) {
            val ids = sequenceOf(File("src/test/resources/opencode/$tier-model-ids.json"),
                File("app/src/test/resources/opencode/$tier-model-ids.json")).first { it.exists() }.readText()
            val provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference" + if (tier == "go") "/go" else "")
            for (element in Json.parseToJsonElement(ids).jsonArray) {
                val id = element.jsonPrimitive.content
                val model = resolver.applyToModel(Model(modelId = id), provider)
                assertNotNull("$tier/$id icon", model.iconUrl)
                assertTrue("$tier/$id tools", ModelAbility.TOOL in model.abilities)
                assertEquals("$tier/$id type", ModelType.CHAT, model.type)
                assertFalse("$tier/$id no chat audio", Modality.AUDIO in model.inputModalities)
            }
        }
    }

    @Test fun openCodeCapsDoNotOverrideNativeProviders() {
        val catalog = sequenceOf(File("../catalog/lastchat_catalog.json"), File("catalog/lastchat_catalog.json")).first { it.exists() }
        val resolver = ModelMetadataResolver { ModelCatalogParser.parse(catalog.readText()) }
        val model = Model(modelId = "kimi-k2.7-code")
        val go = resolver.applyToModel(model, ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference/go"))
        assertEquals(262144, go.contextWindowTokens)
        val vision = resolver.applyToModel(Model(modelId = "nemotron-3.5-super-vl-free"),
            ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference"))
        assertTrue(Modality.IMAGE in vision.inputModalities)
    }
}
