package me.rerere.rikkahub.ui.hooks

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.motionEventSpy
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import sh.calvin.reorderable.ReorderableLazyListState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.transformers.MessageTemplateCache
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
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

/** Real pointer gestures through Calvin + LastChat's ordering, lift and swipe components. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
@OptIn(ExperimentalComposeUiApi::class)
internal abstract class ReorderableListGestureFixture(effectContext: CoroutineContext = EmptyCoroutineContext) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = effectContext)
    protected val source = mutableStateOf(listOf("a", "b", "c", "d", "e", "f"))
    protected val scrollPadding = mutableStateOf(PaddingValues(0.dp))
    protected lateinit var scrollingList: LazyListState
    protected lateinit var controller: ReorderableLazyListState
    protected val show = mutableStateOf(true)
    protected var parentWritesAtDispose: List<List<Any>>? = null
    protected val pointerY = mutableListOf<Float>()
    protected val writes = mutableListOf<List<Any>>()
    protected val rowCompositions = mutableMapOf<String, Int>()
    protected lateinit var order: ReorderableItems<String>

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        startKoin {
            modules(module {
                single<MessageTemplateCache> { object : MessageTemplateCache { override fun invalidateAll() {} } }
                single { SettingsStore(context, AppScope(), QuickSettingsCache(context), SecretKeyManager(SecureStore(context))) }
            })
        }
        val store = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
        runBlocking { withTimeout(20_000) { store.settingsFlow.first { !it.init } } }
    }

    @After fun tearDown() { stopKoin() }

    protected fun mount(
        withSwipe: Boolean = false,
        immediate: Boolean = false,
        initialIndex: Int = 0,
        onSwipe: (String, Int) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MaterialTheme {
                if (show.value) {
                    DisposableEffect(Unit) { onDispose { parentWritesAtDispose = writes.toList() } }
                    val rows = source.value
                    order = rememberReorderableItems(rows, key = { it }, onCommit = writes::add)
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                    val state = rememberAppReorderableLazyListState(listState, order, scrollPadding.value)
                    SideEffect { controller = state; scrollingList = listState }
                    LazyColumn(Modifier.requiredSize(360.dp, 640.dp).testTag("list").motionEventSpy { event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                            pointerY += event.rawY
                        }
                    }, state = listState) {
                        item(key = "header") { Text("Header", Modifier.height(40.dp)) }
                        items(order.items, key = { it }) { id ->
                            val index = order.items.indexOf(id)
                            AppReorderableItem(state, id) {
                                val content: @Composable () -> Unit = {
                                    SideEffect { rowCompositions[id] = (rowCompositions[id] ?: 0) + 1 }
                                    Row(Modifier.fillMaxWidth().height(if (id == "b") 90.dp else 64.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainer).testTag("row:$id")) {
                                        Text(id, Modifier.weight(1f).padding(16.dp))
                                        Box(Modifier.size(48.dp).testTag("handle:$id").then(
                                            if (immediate) Modifier.draggableHandle() else Modifier.longPressDraggableHandle()
                                        ))
                                    }
                                }
                                if (withSwipe) PhysicsSwipeToDelete(
                                    onDelete = {},
                                    onDragProgress = { _, _ -> onSwipe(id, index) },
                                    modifier = Modifier.testTag("swipe:$id"),
                                ) { content() } else content()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    protected fun publishSource(rows: List<String>) {
        compose.runOnIdle {
            source.value = rows
            // The clock is manual: publish before stepping, rather than awaiting an unpumped
            // Choreographer notification after the last frame has already run.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    protected fun grab(id: String) {
        compose.onNodeWithTag("handle:$id").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650)
        compose.waitForIdle()
    }

    protected fun stepFrames(milliseconds: Long) {
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    protected fun moveHandleToListY(id: String, yDp: Int) {
        val list = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("handle:$id").fetchSemanticsNode().boundsInRoot
        val targetY = list.top + list.height * yDp / 640f
        compose.onNodeWithTag("handle:$id").performTouchInput {
            moveBy(Offset(0f, targetY - handle.center.y))
        }
        repeat(10) {
            stepFrames(100)
        }
    }

}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
@OptIn(ExperimentalComposeUiApi::class)
// The scroll loop must receive its request before its coroutine starts. Use queued
// effects here; the baseline tests retain their original immediate-effect coverage.
internal class ReorderableListAutoScrollTest : ReorderableListGestureFixture(StandardTestDispatcher()) {
    @Test fun upwardAutoScrollStartsInTheLargerTopZone() {
        source.value = ('a'..'z').map { it.toString() }
        mount(initialIndex = 8)
        grab("m")
        moveHandleToListY("m", 80)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeBy(100)
            scrollingList.firstVisibleItemIndex < 8
        }
        compose.onNodeWithTag("handle:m").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun downwardAutoScrollRequiresTheSmallerBottomZone() {
        source.value = ('a'..'z').map { it.toString() }
        mount(initialIndex = 8)
        grab("m")
        moveHandleToListY("m", 600)
        assertEquals("40dp from the bottom must not scroll", 8, scrollingList.firstVisibleItemIndex)
        assertEquals(0, scrollingList.firstVisibleItemScrollOffset)
        moveHandleToListY("m", 624)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeBy(100)
            scrollingList.firstVisibleItemIndex > 8
        }
        compose.onNodeWithTag("handle:m").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
    }

}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
@OptIn(ExperimentalComposeUiApi::class)
internal class ReorderableListGestureTest : ReorderableListGestureFixture() {
    @Test fun heldDragDoesNotSaveAndSlowAcknowledgementNeverRollsRowsBack() {
        mount()
        grab("a")
        val startCenter = compose.onNodeWithTag("row:a").fetchSemanticsNode().boundsInRoot.center.y
        compose.onNodeWithTag("handle:a").performTouchInput { moveBy(Offset(0f, 240f)) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertTrue(order.pending.dragging)
        assertNotEquals(source.value, order.items)
        val movedCenter = compose.onNodeWithTag("row:a").fetchSemanticsNode().boundsInRoot.center.y
        assertEquals("Crossing a variable-height neighbor must keep the row under the finger", startCenter + pointerY.last() - pointerY.first(), movedCenter, 2f)
        val final = order.items.toList()
        compose.mainClock.advanceTimeBy(6_000)
        assertTrue("No settings serialization during a held gesture", writes.isEmpty())
        compose.onNodeWithTag("handle:a").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(listOf(final), writes)
        compose.mainClock.advanceTimeBy(7_000)
        assertEquals("A slow save cannot reset the visible order", final, order.items)
        publishSource(source.value + "new")
        compose.onNodeWithTag("row:new").assertExists()
        assertEquals(final + "new", compose.runOnIdle { order.items.toList() })
        publishSource(final + "new")
        assertNull(order.pending.order)
    }

    @Test fun liftAnimationDoesNotRecomposeTheRowEachFrame() {
        mount()
        grab("c")
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        val before = rowCompositions.getValue("c")
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertTrue("Lift frames should only update graphics layers", rowCompositions.getValue("c") - before <= 1)
        compose.onNodeWithTag("handle:c").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(writes.isEmpty())
    }

    @Test fun swipeAfterAStableKeyMoveUsesTheCurrentRowCallback() {
        val reported = mutableListOf<Pair<String, Int>>()
        mount(withSwipe = true) { id, index -> reported += id to index }
        compose.runOnIdle { source.value = listOf("b", "c", "a", "d", "e", "f") }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("swipe:a").performTouchInput {
            down(center)
            moveBy(Offset(-55f, 0f))
            moveBy(Offset(-25f, 0f))
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertTrue(reported.isNotEmpty())
        assertTrue("Callbacks must follow the current index", reported.all { it == "a" to 2 })
        compose.onNodeWithTag("swipe:a").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
    }
    @Test fun toolbarInsetChangesDoNotReplaceTheActiveDragController() {
        mount()
        grab("a")
        val grabbedController = controller
        compose.runOnIdle {
            scrollPadding.value = PaddingValues(top = 20.dp, bottom = 80.dp)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertSame(grabbedController, controller)
        assertTrue(controller.isAnyItemDragging)
        compose.onNodeWithTag("handle:a").performTouchInput { moveBy(Offset(0f, 240f)) }
        compose.mainClock.advanceTimeBy(100)
        assertNotEquals(source.value, order.items)
        compose.onNodeWithTag("handle:a").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertEquals(1, writes.size)
        assertFalse(controller.isAnyItemDragging)
    }

    @Test fun backgroundingFlushesTheLatestOrderEvenWithThePointerStillDown() {
        mount()
        grab("a")
        compose.onNodeWithTag("handle:a").performTouchInput { moveBy(Offset(0f, 240f)) }
        compose.mainClock.advanceTimeBy(100)
        val final = order.items.toList()
        assertNotEquals(source.value, final)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(listOf(final), writes)
    }

    @Test fun disposingFlushesTheOrderBeforeTheParentSavesItsDraft() {
        mount()
        grab("a")
        compose.onNodeWithTag("handle:a").performTouchInput { moveBy(Offset(0f, 240f)) }
        compose.mainClock.advanceTimeBy(100)
        val final = order.items.toList()
        assertNotEquals(source.value, final)
        compose.runOnIdle {
            show.value = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(listOf(final), writes)
        assertEquals("The parent save must see the child reorder", writes, parentWritesAtDispose)
    }

    @Test fun aSecondDragDuringTheDropAnimationKeepsFollowingTheFingerAndSavesTheFinalOrder() {
        mount(immediate = true)
        compose.onNodeWithTag("handle:a").performTouchInput { down(center); moveBy(Offset(0f, 240f)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("handle:a").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("handle:b").performTouchInput { down(center); moveBy(Offset(0f, 300f)) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(controller.isAnyItemDragging)
        val final = order.items.toList()
        compose.onNodeWithTag("handle:b").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(final, writes.last())
        publishSource(writes.first().map { it.toString() })
        assertEquals(final, order.items)
        publishSource(final)
        assertNull(order.pending.order)
    }

}
