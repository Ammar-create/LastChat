# Provider UI, surfaces and streaming review — 2026-10-20

## Account providers and setup

The stock provider names are **ChatGPT** and **Grok**. Only the two old stock
“subscription” labels are normalized; account UUIDs, credentials and custom names
are preserved. OpenCode Go keeps its existing catalog UUID and endpoint, while its
protocol and URL controls are hidden because routing is managed per model.

Connected account cards contain the account identity, status, an account menu and
a standard expandable Connection row. Reconnect, device-code sign-in and sign-out
are menu actions. Disconnected cards have one pill sign-in button. Usage uses the
same left alignment, padding, card shape and footer row; quota requests are bounded
and cancellable. Unknown quota stays unknown.

The model selector restores the compact remaining-allowance rings from the removed
Codex integration (`cd0670ea^:app/.../ui/components/ai/ModelList.kt`). Account providers
and OpenCode Go show quota windows, remaining percentages and reset information,
without also displaying an API balance. Small widths and large fonts can wrap the
indicators without colliding with provider names.

Both first-time setup paths offer OpenCode Go first, followed by ChatGPT and Grok.
The account choices use the settings sign-in component rather than an API-key
prompt. The in-progress account draft survives activity recreation in the setup VM.
The provider list and guided choices scroll; model selection follows successful login.

## Native surface and contrast policy

`ColorScheme.withLastChatContrast` is shared UI code, applied at the Android theme
root and again to assistant-specific palettes. It retains preset/wallpaper accent
hues and an AMOLED dark canvas, and rebuilds a restrained neutral surface hierarchy
instead of relying on OEM container tones. Android's system contrast setting is
observed on supported versions, including live changes.

Normal text/action roles meet a minimum 4.5:1 contrast against each surface. Paired
button, accent-container, error and inverse roles are also checked. Stronger system
contrast raises the target. Surface tint is limited so strongly colored OEM neutral
resources cannot dominate every card. Light floating chrome uses an outline role;
the dark-glass highlight recipe is confined to dark mode.

The audit covered the native theme roots and these consumers:

| Area | Result |
| --- | --- |
| Provider, model, search, MCP, speech and local-model settings | Shared surface roles; 72 inconsistent light/dark card uses across 29 files unified |
| Assistant profile, prompts, tools, context, memory, RAG, skills and lorebooks | Same surface policy and paired foreground roles |
| Main chat, menu, lists, composer and activity panels | Inherit calibrated root/assistant roles; existing motion, shapes and glass behavior retained |
| Onboarding and backup | Same neutral roles, with account setup using the provider components |
| Dialogs, sheets, floating chrome and toasts | Inherit role calibration; light glass borders corrected |
| MCP names and memory error badges | Hardcoded white replaced with the container's correct foreground role |
| API-key controls, provider test results and empty-state labels | Foregrounds follow paired surface, primary and error roles in both modes; static helper text no longer applies a second opacity reduction |
| Status tags | Success foreground uses a readable green tone on its green container in both modes |
| Markdown, tables and embedded Markdown WebView | Inherit corrected roles; blockquote background is painted before its text |
| Overlay activities | Use the same root and assistant theme policy |
| Media viewers, crops, color pickers and terminal | Their intentional image scrims, color swatches and terminal palettes remain explicit |
| Home-screen widget | Avatar-only artwork, independent of the app's surface palette |
| Browser web UI | Separate browser theme; embedded Markdown colors continue to come from Android's current scheme |

Generical's default mark is the Windows stop-screen blue `#0078D7`. The old stock
indigo mark is migrated for the default assistant. Explicit editor choices carry a
version so a later intentional indigo selection is not reset. Legacy azure/cyan
imports and other customized marks retain their chosen colors.

## Codex HTTP 200 and streaming performance

Codex can send valid SSE without Content-Type. The exact behavior is documented
in RikkaHub's `CodexEventStreamTest` at snapshot
`e5bb70730b4b2cb06c2e049168a1ecc89d41f536`. The Android transport supplies the missing
MIME type only for a successful Codex Responses request. Explicit MIME types,
other endpoints and unsuccessful statuses remain strict. Error body details are
captured before OkHttp closes the response. A successful HTTP status is no longer
converted into an authentication/request-status exception, and an already completed
response survives a subsequent connection-close error.

The SSE callback and all four text-protocol adapters apply backpressure instead of
silently dropping deltas when a consumer is slow. A regression reproduces the old
truncation and checks complete delivery through Responses, Chat Completions, Claude
Messages and Gemini streams. Stream requests explicitly accept SSE. Actionable validation
errors are retained rather than replaced with a generic subscription failure.

Streaming changes preserve the reveal controller, fade timing and all five focus
levels. Idle edits snap to the edited text; only a real stream leaves a draining
tail. Empty pauses no longer recompose markdown at display refresh rate. Bubble-run
grouping is memoized. Markdown parsing no longer holds the shared AST cache lock
through a large parse. Blur annotations and glyph paths are decoded once per frame,
and each blur layer is cropped to its settling glyphs instead of allocating a full
paragraph-sized layer. The solid-layout cache no longer writes Compose state while
measuring, avoiding an extra layout/recomposition cycle.

## Verification and limits

Transport tests cover a headerless Codex stream, slow-consumer delivery, explicit
JSON errors, expired auth and MIME handling on unrelated endpoints. Provider tests
cover HTTP 200 transport errors, completion followed by disconnect, and error details.
Theme tests check all six presets in both modes, OEM-like inconsistent surfaces,
contrast and surface ordering. Setup/name/avatar migration tests preserve identity
and custom choices. Streaming tests cover large idle edits and smooth completion tails.

`ProviderAppearanceRenderTest` renders the real account, usage and selector components
with native Robolectric graphics in light/dark mode and at 130% text size. Existing
streaming/code render harnesses check the fade/focus output. These are Android API 35
renders, not a Samsung One UI 9 device or a device-frame-rate benchmark. Live paid
account generation still needs a phone-side check.

## Recording: overlapping assistant generations

The compressed 95-second recording is now accessible. It shows assistant tool
follow-ups changing/reappearing after deletion, with generation controls no longer
matching the updates. The generation registry previously cancelled an old job
without waiting for its final flush, and each job's completion unconditionally
removed the chat's current job. An old completion could therefore hide a newer
stream while both generations still wrote snapshots. History mutations and late
metadata saves could also restore older nodes or switch the visible reply version.

Generation now has one writer lane per conversation. A replacement waits for the
previous stream's final text/reasoning flush; only the matching job is removed
from the registry. Cancelling a queued replacement still tracks any predecessor
that is finishing. Edits, deletions, reply-version changes and explicit history
restores take the same lane. Tool cancellation propagates instead of becoming a
tool error followed by another assistant step. A deleted regeneration target is
ignored. Late metadata saves preserve current message nodes, including deletions;
per-chat database write locks prevent an older save from finishing after a newer
save. The stale-snapshot guard also applies to temporary chats. Clearing an existing
chat persists its empty history, while a new empty draft still creates no row.
Independent chats remain concurrent. Background context summaries also wait for
active generation without cancelling it, then rebase only if the summarized prefix
and prior summary still match; their old input snapshot is never restored.

Regression tests cover 100 rapid regenerations, simultaneous chats, deletion
while cancellation is flushing, queued cancellation, editing followed by a new
reply, late metadata after deletion/version selection, and persistence ordering.
The final deliverable uses **stableRelease**, the normal LastChat application ID
and label, while source changes remain committed on `lastchat_dev`. It uses the
uploaded signing key and ARM64 libraries for the S24 Exynos.
