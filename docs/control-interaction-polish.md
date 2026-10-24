# Control interaction polish — 2026-10-21

Settings edits now publish their new in-memory value before the tap callback
returns. The ordered, app-lifetime persistence lane coalesces bursts into durable
snapshots on IO. Each transform runs once against the latest value. Revision checks
prevent an earlier save or DataStore echo from replacing a later edit. Requests
made before initial loading retain their order and run against loaded settings;
cancelling a caller's acknowledgement does not cancel its edit. One-time migration
writes share the persistence lock and cannot replace newer revisions. Provider
secrets still pass through SecureStore before disk serialization. Message-template
caches invalidate immediately, so sending before a save finishes uses the edit.

Settings, chat, assistant, onboarding, share and backup ViewModels expose the store's
StateFlow directly instead of relaying it through another coroutine and dummy
initial values. Chat model/search changes use immediate edits. Snapshot merging
preserves unchanged provider and assistant collections by reference and only
serializes a conflicting, changed section. Appearance controls preserve unrelated
field edits. Font/display drafts no longer reset whenever any setting changes.

Provider configuration has one shared draft rather than a second local copy reset
by an effect. Debounce handoffs and draft updates run on Main; a background timer
can no longer cancel a newer edit's save. The store publishes a flush synchronously
before the draft is cleared. Turning off master memory also turns off consolidation,
so normalization cannot immediately turn the switch back on.

Haptic controls observe only the haptics preference; explicit overrides need no
settings subscription. Unrelated edits no longer recompose those controls.
Shared preference bindings update Compose synchronously, follow changes of key,
and reread current preferences when notified. Color-mode reads use the current
binding rather than the initial derived-state instance. Read-only switches retain
null callbacks so their enclosing toggle handles the touch. The click modifier
now honors disabled state.

Settings rows/navigation, memory rows, conversation rows, attachments, drawer/back
controls and chat toggle surfaces share a single interruption and reduced-motion
policy for press feedback. Press/release springs do not overshoot. Dropdown arrows
also use a damped transition. Turning reduced motion on during an animation stops
added movement immediately. Android's theme supplies that policy to every themed
window, including the assistant overlay.

The theme observes appearance fields separately. Ordinary settings edits keep the
palette and themed content stable; dynamic palettes are cached against appearance,
configuration and dark mode, with contrast changes handled separately. Typography
uses the same appearance snapshot instead of a second full-settings subscription.

Validation: 161 app, 236 AI and 17 UI-core checks passed (414 total). Coverage includes
six deterministic paused-persistence cases, six native Compose control/theme
scenarios, all nine existing native drag/auto-scroll scenarios, settings merges and
normalization, slow/reordered acknowledgements, lifecycle durability, streaming and
chat generation/history. Light/dark provider renders at regular and enlarged text
sizes were checked; the iOS portability report completed. Physical Samsung frame
times are not measured by these tests.
