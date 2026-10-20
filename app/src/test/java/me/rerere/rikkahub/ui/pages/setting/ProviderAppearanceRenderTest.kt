package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.*
import me.rerere.rikkahub.ui.components.ai.ProviderUsageIndicators
import me.rerere.rikkahub.ui.pages.setting.components.SubscriptionAccountContent
import me.rerere.rikkahub.ui.pages.setting.components.ProviderUsageContent
import me.rerere.rikkahub.ui.theme.Shapes
import me.rerere.rikkahub.ui.theme.presetColorScheme
import me.rerere.rikkahub.ui.theme.withLastChatContrast
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.time.Instant

/** Renders the real account/usage/selector components for visual review; opt in with renderDir. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h900dp-xxhdpi")
class ProviderAppearanceRenderTest {
    @Test fun renderAccountStatesAndSelectorInBothModes() {
        val output = System.getProperty("lastchat.renderDir").orEmpty()
        assumeTrue(output.isNotBlank())
        File(output).mkdirs()
        for (dark in listOf(false, true)) {
            for (connected in listOf(false, true)) render(output, dark, connected, 1f)
            render(output, dark, true, 1.3f)
        }
    }

    private fun render(output: String, dark: Boolean, connected: Boolean, textScale: Float) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val scheme = presetColorScheme("ocean", dark).withLastChatContrast(dark)
        val provider = ProviderSetting.OpenAI(name = "ChatGPT", subscriptionAccount = SubscriptionAccountType.CHATGPT)
        val usage = ProviderUsage(listOf(
            ProviderUsageWindow("5-hour window", 20.0, Instant.parse("2026-10-20T20:00:00Z")),
            ProviderUsageWindow("Weekly", 51.0, Instant.parse("2026-10-24T10:00:00Z"))))
        activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, textScale)) {
                MaterialTheme(colorScheme = scheme, typography = me.rerere.rikkahub.ui.theme.Typography, shapes = Shapes) {
                    Column(Modifier.requiredSize(360.dp, 900.dp).background(scheme.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("ChatGPT", style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
                        SubscriptionAccountContent(provider, SubscriptionAccountState(account = if (connected) "you@example.com" else null),
                            onEdit = {}, onConnect = {}, onCancel = {}, onOpenBrowser = {}, onSignOut = {})
                        if (connected) ProviderUsageContent(provider, Result.success(usage), onRefresh = {}, onBrowse = {})
                        Text("Model selector", style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
                        ProviderUsageIndicators(usage)
                    }
                }
            }
        }
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val view = root.getChildAt(0)
        val width = (360 * density).toInt()
        val height = (900 * density).toInt()
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(output, "provider-${if (dark) "dark" else "light"}-${if (connected) "connected" else "signin"}-$textScale.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        controller.pause().stop().destroy()
    }
}
