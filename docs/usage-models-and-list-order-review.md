# Usage, model selection and list ordering — 2026-10-20

## Usage loading

Settings and model-picker headers share an account-scoped, in-memory usage cache.
Successful reads remain visible during refresh and transient failures. Reads are
coalesced, expire after 60 seconds, and have a 20-second timeout. A credential or
account-version change invalidates that provider's previous entry. Cache entries
are bounded; neither quotas nor credentials are written to disk by the cache.

On first load, measured ghost rows reserve the same label, indicator and reset-line
geometry as the documented usage windows. Missing windows and unavailable data do
not collapse the reserved area. A native rendering regression checks identical
settings-card and picker heights before/after loading ChatGPT and OpenCode Go,
including 130% text size. Picker cells wrap at larger text sizes and keep the
remaining percentage visible. The skeleton uses static surface roles rather than
a continuously running shimmer.

## Model selection and metadata

Signing in no longer inserts all discovered models into a provider. Authentication
finishes independently of model discovery. First-time account setup checks the
account connection, then fetches available models for an initially empty selection.
Guided API-key setup also asks for explicit model selection. Late discovery results
cannot navigate back into an abandoned setup step. The ComfyUI preset no longer
adds a fictitious `model.safetensors` selection. Existing user selections survive
reconnection.

New chat models assume tool support. Embedding/image/STT models and explicit
provider/user capability opt-outs retain their settings. Missing API metadata no
longer silently disables tools on an otherwise unknown chat model.

Public model lists captured on 2026-10-20 contain 37 OpenCode Go and 85 Zen entries.
Fixtures cover all 122 entries, including preview/free/contributor IDs. New
families and provider bindings supply icons and capabilities for Muse, LongCat,
Beam, HY models and opaque OpenCode previews. Published service metadata supplies
vision, reasoning and context information; unknown preview limits are not invented.
Service-specific limits use endpoint constraints, so custom provider UUIDs work
and native-provider defaults are preserved. Go's MiniMax M2.7 uses Messages, matching
its published SDK mapping.

Sources: https://opencode.ai/inference/go/v1/models,
https://opencode.ai/inference/v1/models and https://models.dev/api.json.

## Ordering and expansion

All 15 drag-list sites now render an immediate local ID order. Ordinary writes
are coalesced after a short pause; leaving a screen flushes any outstanding order.
Provider drafts already debounce persistence, so model/key orders update those
drafts immediately, before a parent lifecycle flush can run. Late storage
acknowledgements cannot roll the visible list back to an earlier order.

Coverage includes providers, provider models, favorites, assistants, search,
TTS providers and voices, skills, lorebooks and their entries, API-key pools,
installed local language models and installed speech models. Moves use stable item
keys rather than offsets through headers or filtered rows. Reordering preserves
current objects, concurrent additions/deletions and hidden-row positions.

ID-bearing settings arrays merge field edits separately from their order.
Provider drafts, assistant edits and TTS voices therefore preserve newer orderings
rather than replacing an entire stale snapshot. Assistant edits are queued as
deltas instead of dropping older saves that may contain a different field's edit.
Short local-model persistence and edit callbacks use application-owned jobs.
Favorites use the reorder library's existing placement animation once.

Account connection options and timeline details use the shared finite expansion
animation. Collapse spacing follows the child height; timeline rows do not add a
second size spring over the same transition. Parent-owned panel motion and reduced
motion remain respected.

## Verification

The broad run exercised 660 app cases plus 323 cases in common, AI, UI core,
speech and local inference. Ten explicitly opt-in avatar-video cases were skipped.
One old assertion expected a preselected ComfyUI placeholder; it was updated to
assert the requested empty selection and passed in the final focused run. The final
focused app run passed 139 cases; the other five modules passed 323 cases.

The unchanged localization audit reports 405 missing Arabic keys and 337 missing
Simplified Chinese keys. Its source and audited resources are identical to the
branch baseline. Those two unrelated audits were excluded from the broad rerun
using an external Gradle init script; no repository test or resource was weakened.
Android's normal English fallback remains available. Localization was not changed.

The broad native-render run exceeded the test machine's memory budget initially;
rerunning with sequential, bounded worker batches completed. Final component
geometry/render checks, shared expansion/contrast checks, ordering/merge/cache
regressions, protocol and catalog checks, and the iOS portability report passed.
No physical Samsung device or live paid subscription was available for testing.
