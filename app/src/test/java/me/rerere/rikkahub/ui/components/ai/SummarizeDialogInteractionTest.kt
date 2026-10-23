package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.MessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.hooks.PremiumHaptics
import me.rerere.rikkahub.ui.modifier.LastChatBlur
import me.rerere.rikkahub.ui.modifier.LocalLastChatBlur
import me.rerere.rikkahub.ui.modifier.lastChatBlurSource
import me.rerere.rikkahub.ui.motion.LocalMotionPolicy
import me.rerere.rikkahub.ui.motion.MotionPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SummarizeDialogInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val noHaptics = PremiumHaptics(object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }, null, false)

    private fun frames() {
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
    }

    @Test fun opensAndExpandsWithMotionAndGlassEnabled() {
        val assistant = Assistant()
        val conversation = Conversation(assistantId = assistant.id,
            messageNodes = (0 until 14).flatMap {
                listOf(MessageNode.of(UIMessage.user("Question $it")),
                    MessageNode.of(UIMessage.assistant("Answer $it")))
            }, contextSummary = "Saved details", contextSummaryUpToIndex = 3)
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            val haze = rememberHazeState()
            MaterialTheme {
                CompositionLocalProvider(LocalLastChatBlur provides LastChatBlur(true, haze),
                    LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                    Box(Modifier.fillMaxSize().lastChatBlurSource()) {
                        Button(onClick = { open = true }) { Text("Open") }
                    }
                    if (open) ContextManagementDialog(conversation, assistant,
                        Model(modelId = "chat", contextWindowTokens = 32_000), status = null,
                        onSummarize = {}, onCancel = {}, onUpdateConversation = {},
                        onDismiss = { open = false }, haptics = noHaptics)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open").performClick()
        frames()
        compose.onNode(hasText("Summarize") and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Summary").performClick()
        frames()
        compose.onNodeWithText("Edit").assertExists()
        compose.onNodeWithText("Details").performClick()
        frames()
        compose.onNodeWithText("Close").performClick()
        frames()
        compose.onAllNodesWithText("Summarize").assertCountEquals(0)
    }
    @Test fun previewFailureCanBeRetriedWithoutClosingTheDialog() {
        val assistant = Assistant()
        val conversation = Conversation(assistantId = assistant.id,
            messageNodes = (0 until 14).flatMap {
                listOf(MessageNode.of(UIMessage.user("Question $it")),
                    MessageNode.of(UIMessage.assistant("Answer $it")))
            })
        var attempts = 0
        var submitted = 0
        compose.setContent {
            MaterialTheme {
                ContextManagementDialog(conversation, assistant,
                    Model(modelId = "chat", contextWindowTokens = 32_000), status = null,
                    onSummarize = { submitted++ }, onCancel = {}, onUpdateConversation = {},
                    onDismiss = {}, haptics = noHaptics,
                    estimate = { chat, character, model ->
                        if (++attempts == 1) throw IllegalArgumentException("Invalid preview")
                        estimateContextDialog(chat, character, model)
                    })
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Preview unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Summarize") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Preview unavailable").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Preparing…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasText("Summarize") and hasClickAction()).assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(2, attempts)
        org.junit.Assert.assertEquals(1, submitted)
    }

}
