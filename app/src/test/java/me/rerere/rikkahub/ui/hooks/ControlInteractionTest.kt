package me.rerere.rikkahub.ui.hooks

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.transformers.MessageTemplateCache
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.motion.LocalMotionPolicy
import me.rerere.rikkahub.ui.motion.MotionPolicy
import me.rerere.rikkahub.ui.motion.rememberControlPressValue
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
class ControlInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val appScope = AppScope()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var store: SettingsStore

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = SettingsStore(context, appScope, QuickSettingsCache(context), SecretKeyManager(SecureStore(context)),
            StandardTestDispatcher(scheduler))
        startKoin { modules(module {
            single<MessageTemplateCache> { object : MessageTemplateCache { override fun invalidateAll() {} } }
            single { store }
        }) }
        runBlocking { withTimeout(20_000) {
            while (store.settingsFlow.value.init) { scheduler.runCurrent(); delay(10) }
        } }
    }

    @After fun tearDown() {
        runBlocking { withTimeout(20_000) {
            val draining = launch(kotlinx.coroutines.Dispatchers.Default) { store.awaitQueuedWrites() }
            while (!draining.isCompleted) { scheduler.runCurrent(); delay(10) }
            draining.join()
        } }
        appScope.cancel()
        stopKoin()
    }

    private fun frames(ms: Long) {
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test fun switchKeepsEachNewStateForTheWholeAnimationWhileDiskIsPaused() {
        val seen = mutableListOf<Boolean>()
        compose.setContent {
            val settings by store.settingsFlow.collectAsState()
            val checked = settings.enableWebSearch
            SideEffect { seen += checked }
            MaterialTheme {
                HapticSwitch(checked, { enabled ->
                    store.enqueueUpdate { it.copy(enableWebSearch = enabled) }
                }, Modifier.testTag("switch"))
            }
        }
        compose.mainClock.autoAdvance = false
        val start = store.settingsFlow.value.enableWebSearch
        repeat(6) { tap ->
            val expected = if (tap % 2 == 0) !start else start
            compose.onNodeWithTag("switch").performTouchInput { click(center) }
            repeat(10) {
                frames(16)
                if (expected) compose.onNodeWithTag("switch").assertIsOn()
                else compose.onNodeWithTag("switch").assertIsOff()
            }
            assertEquals(expected, store.settingsFlow.value.enableWebSearch)
        }
        assertEquals(listOf(start) + (0 until 6).map { if (it % 2 == 0) !start else start }, seen.fold(mutableListOf<Boolean>()) { values, value ->
            if (values.lastOrNull() != value) values += value
            values
        })
    }

    @Test fun preferenceBindingsFollowChangedKeysAndColorModeReadsItsCurrentValue() {
        val selectedKey = mutableStateOf("first")
        lateinit var flag: MutableState<Boolean>
        lateinit var mode: MutableState<ColorMode>
        compose.setContent {
            flag = rememberSharedPreferenceBoolean(selectedKey.value)
            mode = rememberColorMode()
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            flag.value = true
            assertTrue(flag.value)
            ColorMode.entries.forEach { mode.value = it; assertEquals(it, mode.value) }
            selectedKey.value = "second"
        }
        frames(32)
        compose.runOnIdle {
            assertFalse(flag.value)
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("first", false).apply()
            flag.value = true
            assertTrue(prefs.getBoolean("second", false))
            assertFalse(prefs.getBoolean("first", true))
        }
    }

    @Test fun readOnlySwitchLetsItsParentHandleTheTapOnceAndDisabledButtonsStayDisabled() {
        var toggles = 0
        var clicks = 0
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            MaterialTheme {
                Row(Modifier.toggleable(checked, onValueChange = { checked = it; toggles++ })) {
                    HapticSwitch(checked, null, Modifier.testTag("thumb"))
                    Box(Modifier.size(48.dp).onClick(enabled = false) { clicks++ }.testTag("disabled"))
                }
            }
        }
        compose.onNodeWithTag("thumb", useUnmergedTree = true).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, toggles)
        compose.onNodeWithTag("disabled", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("disabled", useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(0, clicks)
    }

    @Test fun unrelatedEditsKeepThePaletteAndThemeContentStableButAppearanceEditsStillApply() {
        lateinit var scheme: androidx.compose.material3.ColorScheme
        var appearances = 0
        compose.setContent {
            RikkahubTheme {
                val current = MaterialTheme.colorScheme
                SideEffect { scheme = current; appearances++ }
            }
        }
        compose.mainClock.autoAdvance = false
        val initial = scheme
        val initialAppearances = appearances
        compose.runOnIdle { store.enqueueUpdate { it.copy(enableWebSearch = !it.enableWebSearch) } }
        frames(100)
        assertSame(initial, scheme)
        assertEquals(initialAppearances, appearances)
        compose.runOnIdle { store.enqueueUpdate { it.copy(dynamicColor = !it.dynamicColor) } }
        frames(100)
        assertNotSame(initial, scheme)
    }

    @Test fun pressFeedbackRetargetsWithoutOvershootAndReducedMotionKeepsGeometryFixed() {
        val pressed = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        lateinit var scale: State<Float>
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduced.value)) {
                scale = rememberControlPressValue(pressed.value, 0.94f, "testPress")
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(4) {
            compose.runOnIdle { pressed.value = true }
            repeat(6) { frames(16); assertTrue(scale.value in 0.9399f..1.0001f) }
            compose.runOnIdle { pressed.value = false }
            repeat(6) { frames(16); assertTrue(scale.value in 0.9399f..1.0001f) }
        }
        compose.runOnIdle { reduced.value = true; pressed.value = true }
        frames(32)
        assertEquals(1f, scale.value, 0f)
    }
}
