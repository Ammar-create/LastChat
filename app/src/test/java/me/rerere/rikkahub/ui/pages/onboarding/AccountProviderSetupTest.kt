package me.rerere.rikkahub.ui.pages.onboarding

import android.app.Application
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.SubscriptionAccountType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.normalizeAccountProviderNames
import me.rerere.rikkahub.ui.pages.setting.components.SPECIAL_PROVIDER_PRESETS
import me.rerere.rikkahub.ui.pages.setting.components.toProviderSetting
import me.rerere.rikkahub.ui.pages.setting.components.withSpecialProviderPresets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountProviderSetupTest {
    @Test fun accountChoicesAreAvailableWithoutANetworkCatalogAndOpenCodeIsFirst() {
        val presets = emptyList<me.rerere.rikkahub.ui.pages.setting.components.ProviderPreset>()
            .withSpecialProviderPresets().prioritizeSetupProviders()
        assertEquals(listOf("OpenCode Go", "ChatGPT", "Grok"), presets.take(3).map { it.name })
        assertTrue(presets.take(3).all { it.setupRecommended })
        val accounts = presets.take(3).drop(1).map { it.toProviderSetting() as ProviderSetting.OpenAI }
        assertEquals(listOf(SubscriptionAccountType.CHATGPT, SubscriptionAccountType.GROK), accounts.map { it.subscriptionAccount })
        assertTrue(accounts.all { it.apiKey.isEmpty() })
    }

    @Test fun catalogGoPresetKeepsItsIdAndConnectionWhenSpecialPresetsAreMerged() {
        val go = SPECIAL_PROVIDER_PRESETS.first { it.name == "OpenCode Go" }
        val merged = listOf(go.copy(description = "Catalog description")).withSpecialProviderPresets().prioritizeSetupProviders()
        assertEquals(1, merged.count { it.name == go.name })
        assertEquals(go.id, merged.first().id)
        assertEquals("Catalog description", merged.first().description)
    }

    @Test fun onlyLegacyAccountNamesAreRenamed() {
        val old = ProviderSetting.OpenAI(name = "ChatGPT subscription", subscriptionAccount = SubscriptionAccountType.CHATGPT)
        val custom = ProviderSetting.OpenAI(name = "My work account", subscriptionAccount = SubscriptionAccountType.GROK)
        val api = ProviderSetting.OpenAI(name = "Grok subscription")
        val fixed = Settings(providers = listOf(old, custom, api)).normalizeAccountProviderNames()
        assertEquals(listOf("ChatGPT", "My work account", "Grok subscription"), fixed.providers.map { it.name })
        assertEquals(old.id, fixed.providers.first().id)
    }
}
