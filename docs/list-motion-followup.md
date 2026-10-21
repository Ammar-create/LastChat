# List motion follow-up — 2026-10-21

All 15 drag-list sites now share the same drag controller adapter and row motion.
Rows keep their visual size while dragging. An animated elevation change supplies
lift feedback; scaling a drag handle's ancestors also scales its local pointer
deltas, causing the row to drift away from the finger. Native pointer tests check
that crossing a variable-height neighbor preserves the finger-to-row position.
Placement uses one critically damped spring; lift frames update a graphics layer
instead of recomposing row content. Reduced motion disables these added effects.
Changes to toolbar/inset padding are deferred until the gesture and settling finish,
so they cannot replace the library controller under an active pointer.

The shared order state keeps an immediate, stable-key order throughout a held drag.
Persistence coalesces after dropping; lifecycle pause and disposal flush unfinished
orders. Matching snapshots only acknowledge submitted requests. Slow writes do not
trigger a timed rollback. A synchronous draft acknowledgement cannot leave an old
committed marker, and a drag that returns to its starting order produces no write.
The provider page also flushes at stop, covering a child order flushed at pause.

Swipe neighbors use one shared stable-key coordinator, including MCP lists. Only
nearby row scopes read the changing offset. Threshold transitions happen in gesture
callbacks, replacing state writes performed while composing rows. Reordering and
removing rows cannot leave the coordinator attached to an obsolete index.

Swipe callbacks stay current across keyed item moves. Changes to deletion permission
restart recognition directly, without recreating the complete row subtree. A new
swipe cancels settling synchronously; pointer deltas no longer launch competing
snap coroutines. Beginning a vertical reorder closes open swipes and disables their
horizontal recognizers. Closed action buttons cannot intercept touches invisibly.

Favorites resolve catalog metadata before the drag loop and reorder only visible
model types, preserving hidden favorites in their existing stored slots.

The upstream `af8724c1` context-compression changes are included. The summary merge
retains its new batching and retention behavior and applies results through the
existing per-conversation mutation lane and summarized-prefix validation.

Verification uses API 35 Robolectric native graphics and real Compose pointer
injection, plus ordering, cache, persistence, context and motion regression tests.
The focused checks passed 108 app, 236 AI and 17 UI-core cases (361 total), including
seven pointer-gesture scenarios. Physical Samsung frame times remain outside this
test environment.
