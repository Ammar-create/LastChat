# AGENTS.md — LastChat Agent Development Playbook & Incident Post-Mortem

This document serves as the persistent technical playbook for any AI agent or developer working on `LastChat`. It documents the hardware constraints, cloud CI/CD architecture, the single-APK packaging rule, all implemented features, and every failure/gotcha encountered during development so mistakes are never repeated.

---

## 1. Environment & Hardware Constraints (Critical)

- **Target Device:** Lenovo Tab M9 (TB310FU), Android 13, ARM64 (`arm64-v8a`), 4GB RAM (~1.2GB free available RAM).
- **Mobile-Only Environment:** The developer has no desktop/PC. All operations run directly from the device inside a sandboxed Linux PRoot environment (`/workspace`).
- **NEVER Build APKs Locally in PRoot:**
  - Running `./gradlew assembleDebug` or `./gradlew assembleRelease` locally will spawn the Gradle Daemon, Kotlin Compile Daemon, and KSP worker.
  - Memory consumption spikes by 800MB–1.2GB within seconds, dropping free RAM below 500MB.
  - Android's Low Memory Killer (LMK) will aggressively terminate the host process (`excp.rikkahub`), aborting the conversation, dropping ongoing tasks, and freezing the UI.
  - If a Gradle or Java daemon ever gets spawned locally by accident, kill it immediately:
    ```bash
    killall -9 java 2>/dev/null
    ```

---

## 2. Fork Identity & Coexistence Architecture

- **Fork Application ID:** `lastchat.rikkafork.velvet` (configured in `app/build.gradle.kts`).
- **Upstream Application ID:** `lastchat.rikkafork.cocolal`.
- **Why Distinct Application IDs Are Mandatory:**
  - Android strictly enforces signature verification on package updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
  - The developer's official releases on GitHub are signed with their private key, while CI builds of this fork use a deterministic debug keystore.
  - Re-identifying the fork as `lastchat.rikkafork.velvet` allows our custom build to install and run **side-by-side** with the upstream release without signature collisions, data loss, or requiring uninstallation of the user's working setup.
- **Required Identity Touch-points:**
  1. `app/build.gradle.kts`: `defaultConfig { applicationId = "lastchat.rikkafork.velvet" }`
  2. `app/src/main/AndroidManifest.xml`: `android:taskAffinity="lastchat.rikkafork.velvet.assistant"`
  3. `.github/workflows/build-*.yml`: Mock `google-services.json` must declare clients for `lastchat.rikkafork.velvet` and `lastchat.rikkafork.velvet.dev`.

---

## 3. Hard Rule: Single ARM64 APK & Stable Release Minification

When building or modifying CI/CD workflows for LastChat:

1. **Build ONLY Tablet Native Architecture (`arm64-v8a`):**
   - **NEVER** package or upload all 8 ABI/flavor combinations (`**/*.apk`), which creates an 850MB+ bloated archive.
   - Always pass ABI split flags to constrain compilation to arm64:
     ```bash
     -Plastchat.abi.splits=true
     ```
2. **Always Use Minified Stable Release for Daily Driving:**
   - **Dev Debug APK:** Heavy (~106 MB), unminified, debug logging active, and noticeably laggy during Compose rendering and animations.
   - **Stable Release APK:** Shrunk to **~66 MB zipped (~81 MB on disk)** using R8 code shrinking and resource optimization.
   - **Enabling R8 Shrinker:** In `app/build.gradle.kts`, `isMinifyEnabled` and `isShrinkResources` are gated behind the Gradle property `lastchat.release.minify`. You **must** pass `-Plastchat.release.minify=true`:
     ```bash
     ./gradlew :app:assembleStableRelease -Plastchat.release.minify=true -Plastchat.abi.splits=true --no-parallel --stacktrace
     ```
   - **Artifact Path in `actions/upload-artifact@v4`:**
     ```yaml
     path: app/build/outputs/apk/stable/release/*arm64-v8a*.apk
     ```

---

## 4. GitHub Actions CI/CD Playbook

### Required Toolchain
1. **Java:** Use JDK 21 (`actions/setup-java@v4`, distribution `temurin`).
2. **Bun is MANDATORY:** The Gradle task `:web:installWebUiDeps` directly executes `bun` in `web-ui`. If missing, build fails immediately. Include `oven-sh/setup-bun@v2`.
3. **Node & PNPM:** PNPM v11 (`pnpm/action-setup@v4`) and Node 22 (`actions/setup-node@v4`).
4. **Mock `google-services.json`:** Must include client objects for BOTH application IDs (`lastchat.rikkafork.velvet` and `lastchat.rikkafork.velvet.dev`).
5. **Secure Deterministic Keystore via GitHub Secrets:**
   - Instead of committing keystores to git, the permanent release keystore is stored as repository secret `SIGNING_KEYSTORE_BASE64` in `Ammar-create/LastChat`.
   - Workflows decode it: `echo "$SIGNING_KEYSTORE_BASE64" | base64 -d > app/debug.keystore`.
   - Fallback for PRs: ephemeral `keytool -genkey` fallback so builds outside the main repo don't break.
   - Local backup stored on device: `/workspace/.secrets/lastchat-release.keystore` and `.b64`.
   - Certificate SHA256: `AA:DD:E5:26:3E:A7:3C:61:20:6D:33:AF:40:47:AA:BA:86:DE:E3:D5:0B:F8:4F:96:86:0A:03:9D:FA:C2:96:8A`.

### Runner Memory Configuration (`gradle.properties`)
- `kotlin.daemon.jvmargs=-Xmx4096m -XX:+UseParallelGC -Dfile.encoding=UTF-8` (never cap at 2048m — KSP and Compose IR will OOM).
- `org.gradle.jvmargs=-Xmx4096m -XX:+UseParallelGC -Dfile.encoding=UTF-8`.
- `--no-parallel` on 2-vCPU runners.

### GitHub Actions Polling & Azure Blob Redirect
- Full clean builds take **11 to 15 minutes** on GitHub runners. Never use 20–30s rapid tool loops. Poll with long intervals (2.5-minute windows, timeout 180s+).
- **Azure Blob Storage Redirect Trap:** Endpoints like `/actions/artifacts/{id}/zip` return a `302 Found` to an Azure URL with signed credentials. HTTP clients forwarding GitHub `Authorization: Bearer ...` headers to Azure get rejected with **HTTP 403**.
- **Fix:** Intercept the redirect, strip the Authorization header, and fetch the Azure URL unauthenticated.

---

## 5. On-Device APK Push & Silent Install (Zero-Tap)

Moving APKs from the sandboxed workspace to the Android host:
1. **Workspace socket sender (background):**
   ```python
   import socket
   blob = open("/workspace/apk_out/app.apk", "rb").read()
   s = socket.socket()
   s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
   s.bind(('127.0.0.1', 9876))
   s.listen(1)
   c, _ = s.accept()
   c.sendall(blob)
   c.close()
   s.close()
   ```
2. **Shizuku netcat pull to `/data/local/tmp/`:**
   ```bash
   nc -w 8 127.0.0.1 9876 > /data/local/tmp/lc.apk
   ```
3. **SELinux / FUSE Trap:**
   - Android 13 SELinux policy denies `system_server` read permissions on FUSE-mounted storage (`/sdcard/` / `/storage/emulated/0/`).
   - Running `pm install /sdcard/...` fails with:
     ```text
     avc: denied { read } for scontext=u:r:system_server:s0 tcontext=u:object_r:fuse:s0
     ```
   - **Fix:** Always stage the APK under `/data/local/tmp/`, which has standard `shell` SELinux contexts readable by `system_server`.
4. **Silent Install & Cleanup:**
   ```bash
   pm install -r /data/local/tmp/lc.apk && rm -f /data/local/tmp/lc.apk
   ```

---

## 6. Architecture: Dynamic Memory Summary Profile (`feat/memory-summary`)

### High-Level Design
Synthesizes fragmented episodic and semantic user memories into an elegant, high-level profile organized into thematic Material 3 cards. It is injected into prompt generation under `<user_memory_summary>` via `GenerationHandler` and `MemoryContextPlanner`.

### Core Architectural Pillars

1. **Local Offline Lexical RAG & Grounding (0ms Latency, Zero API Cost):**
   - When users issue commands in the "Ask or update" bar, the assistant grounds the model using existing memories.
   - **Why NOT remote embeddings?** Remote embedding models introduce high network latency, cost credits, and frequently fail with 404s on custom proxy endpoints or third-party OpenAI-compatible providers.
   - **Implementation:** Fast lexical keyword scoring via `MemoryVectorMath.keywordScore(userInstruction, memory.content)` directly over the assistant's SQLite memory store. The top 6 matches are injected into the prompt as grounding context with 0ms latency and zero remote calls.

2. **Block-Level Delta Patching (Efficiency Architecture):**
   - Full-document rewrites waste tokens, increase latency, and cause models to accidentally drop untouched sections.
   - The model is instructed to emit JSON deltas:
     - `{"action": "update_section", "target_section": "...", "section": {...}}`
     - `{"action": "add_section", "section": {...}}`
     - `{"action": "delete_section", "target_section": "..."}`
   - `applySectionPatch()` surgically merges deltas while leaving unaffected sections intact.

3. **Two-Way Database Synchronization:**
   - Summary edits sync durable facts back to the core database.
   - The model emits `raw_memory_updates` (`create`, `update`, `delete`).
   - Targets are matched via offline keyword scoring and executed against `MemoryRepository`.
   - Protected by `isSyncingRawUpdates` lock to prevent cyclic auto-regeneration loops.

4. **Fault-Tolerant Multi-Stage Parser & Auto-Healing:**
   - Strips reasoning/thinking tags (`<think>...</think>`).
   - Pre-sanitizes raw responses: converts literal unescaped newlines inside strings to `\n` to prevent JSON decoder aborts.
   - Stage 1: Lenient `JsonObject` parser handling flat fields, nested `section` objects, and dynamic keys.
   - Stage 2: Strict `MemorySectionPatch` decoding.
   - Stage 3: Flexible `dive_deeper` parsing supporting both string arrays (`["prompt 1", "prompt 2"]`) and structured objects.
   - Stage 4: Regex delta recovery for malformed JSON.
   - Stage 5: Markdown header fallback (`## Section`).
   - Stage 6: Conversational text fallback protected by `looksLikeRawJson()` guard (strictly refuses to dump raw JSON into section content).
   - Auto-healing: `sanitizeMemorySummaryData()` recursively detects and extracts valid content from legacy/corrupted sections that had raw JSON strings accidentally stored in them.

5. **Upstream Merge & Coexistence (72 Commits from `LastChat_dev`):**
   - Merged commit `e7b0d276` cleanly into fork branch `feat/memory-summary` (commit `b4b2c598`).
   - Upstream introduced its own `ConversationSummarizer`, `ContextCompressionPolicy`, and `SmartContextManager`.
   - **Coexistence:** Upstream's summarizer manages *short-term conversation history compression*, whereas our feature provides the *durable cross-conversation user memory profile* under `<user_memory_summary>`. Both operate in harmony without conflict.
   - **Symbol Migration Audit:**
     - `ContextTokenEstimator` moved from its own file into `ContextAccounting.kt` (same package `me.rerere.ai.context`, full binary compatibility).
     - `limitContext` relocated as an extension function in `ai/src/main/java/me/rerere/ai/ui/MessageUtils.kt`.

---

## 7. Known Upstream & Provider Quirks

### Aqua / DeepSeek Cloudflare 502 Bad Gateway on Full Regeneration
- **Symptom:** In "Memory Summary", clicking "Regenerate" yields a `502 Bad Gateway` (HTML Cloudflare page from `api.aquadevs.com`), while "Ask or update" edits work perfectly.
- **Root Cause:**
  - `askOrUpdate` sends only the current summary + **top 6 keyword-matched memories** (small payload, short completion).
  - `fullRegenerate` dumps **every memory in SQLite verbatim** plus the schema into one giant prompt.
  - On cheaper flash backends (`deepseek-v4.1-flash`), the massive input + extended JSON generation exceeds the upstream origin timeout, causing Cloudflare to emit a 502 Bad Gateway error.
- **Remedy / Future Hardening:**
  - Map-reduce chunked summarization for memory pools > 50 entries.
  - Retry-on-5xx logic with automatic fallback to secondary models (`gemini-3.8-flash` or `gpt-oss`).
  - Truncate HTML error bodies in `MemorySummaryPage.kt` so raw Cloudflare error pages are never dumped into the UI banner.

---

## 7. Known Upstream & Provider Quirks

### Aqua / DeepSeek Cloudflare 502 Bad Gateway on Full Regeneration
- **Symptom:** In "Memory Summary", clicking "Regenerate" yields a `502 Bad Gateway` (HTML Cloudflare page from `api.aquadevs.com`), while "Ask or update" edits work perfectly.
- **Root Cause:**
  - `askOrUpdate` sends only the current summary + **top 6 keyword-matched memories** (small payload, short completion).
  - `fullRegenerate` dumps **every memory in SQLite verbatim** plus the schema into one giant prompt.
  - On cheaper flash backends (`deepseek-v4.1-flash`), the massive input + extended JSON generation exceeds the upstream origin timeout, causing Cloudflare to emit a 502 Bad Gateway error.
- **Remedy / Future Hardening:**
  - Map-reduce chunked summarization for memory pools > 50 entries.
  - Retry-on-5xx logic with automatic fallback to secondary models (`gemini-3.8-flash` or `gpt-oss`).
  - Truncate HTML error bodies in `MemorySummaryPage.kt` so raw Cloudflare error pages are never dumped into the UI banner.

---

## 8. UI Performance & Mobile Navigation Architecture

### Chat Model Search Keyboard Dismissal Fix (`MinimalChatInput.kt`)
- **Symptom:** In the chat screen composer, opening the `+` menu $\rightarrow$ Model Picker and typing a single letter in the search field kicked the user out of the sheet directly back to the chat screen.
- **Root Cause:**
  - `MinimalChatInput.kt` had a global listener:
    ```kotlin
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) showPicker = false
    }
    ```
  - `WindowInsets.isImeVisible` tracks keyboard visibility globally for the window. When the user focused the search field inside the nested model picker sheet, IME opened, immediately triggering `showPicker = false` and dismissing the bottom sheet.
- **Resolution:**
  - Removed the `LaunchedEffect(imeVisible)` auto-dismissal.
  - Replaced it with explicit focus tracking on the composer's text input (`isFocused`). The picker now only dismisses when the primary chat message input field itself gains focus, leaving nested search fields inside sheets unaffected.

### Assistant Settings Memory Pagination (10 Per Page) (`AssistantMemorySubPage.kt`)
- **Symptom:** Opening the Assistant Memory settings on devices with large memory counts (e.g. 300+ memories) froze the UI for several seconds or caused severe jank.
- **Root Cause:**
  - The page used a vertical scrollable `Column` containing an unvirtualized `displayMemories.forEachIndexed { ... MemoryItem(...) }`.
  - For 300 memories, Compose instantiated and measured >5,000 animated nodes (`animateFloatAsState`, `animateDpAsState`, springs, haptics) on the main thread simultaneously.
- **Resolution:**
  - Implemented 10-per-page slicing (`pagedMemories`) directly in `ManageMemoriesSection`.
  - Added a clean Material 3 navigation toolbar at the bottom: `< Prev`, `Page X of Y (Start–End of Total)`, and `Next >`.
  - Tapping the page counter pill opens a "Jump to Page" numerical dialog for direct navigation without multi-tapping.
  - Search, sort, and tab switching automatically reset to page 1 (`currentPage = 0`).
  - Deep-linking from context sources (`scrollToMemoryId`) calculates target page `targetIndex / 10`, navigates to that page, and opens the editor dialog.

---

## 9. Open Ticket / Handoff: Provider Model Fetching Hang

### Observed Symptom
- In `SettingProviderDetailPage.kt` (Provider Settings), fetching/reloading models from a remote provider causes the UI to hang.

### Progress & What Was Changed (`commit ad10b453`)
- `resolveProviderModels` and `syncFreshModelMetadata` were previously invoked on `Dispatchers.Main` inside `reloadApiModels()`.
- Pushed model resolution and metadata sync to `Dispatchers.IO` / `Dispatchers.Default`.
- Added an $O(1)$ lowercase `modelId` dictionary lookup before falling back to fuzzy matching in `syncFreshModelMetadata`.
- Wrapped `filteredModels` and selection checks in `remember` inside `SettingProviderDetailPage.kt`.

### Outstanding Diagnosis for Next Session
1. **Network Layer (`OpenAIProvider.kt` / `PlatformHttpClient`):**
   - Check `listModels(providerSetting: ProviderSetting.OpenAI)`.
   - Notice that if `providerSetting.isLikelyOllama()` is true, it calls `/api/ps`. If Ollama is unreachable or firewalled, does this request hang indefinitely due to missing socket read/connect timeouts?
   - OpenRouter calls `/models?output_modalities=embeddings`. Verify if the second call is blocking or slow.
2. **Recomposition / State Loop:**
   - Does `onUpdateProvider(updatedProvider)` mutate the provider in `SettingsStore`, causing `SettingProviderDetailPage` to re-compose its entire tab pager and trigger recursive `LaunchedEffect(apiModelCacheKey)`?
   - Check if `apiModelCacheKey` changes during `syncFreshModelMetadata` (e.g., if model IDs or hashes fluctuate).
3. **LazyColumn Item Keys:**
   - Verify keys in `LazyColumn` for API model selection dialog (`showPicker` in `SettingProviderDetailPage.kt`). If items lack stable keys or if item composables are heavy, scrolling or rendering large model sets (e.g., OpenRouter's 400+ models) stalls the main thread.

---

## 10. Incident Post-Mortem Log

| Run ID / Date | Failure Point | Root Cause | Permanent Resolution |
|---|---|---|---|
| `38121608321` | `:app:processDevDebugGoogleServices` | `google-services.json` missing on CI runner | Mock Google Services step added to workflow |
| `38121898636` | `:app:processDevDebugGoogleServices` | Mock only contained stable package, missing `.dev` | Added both package clients to mock JSON |
| `38122118792` | `:app:compile*DebugKotlin` | `assistant.id` (`Uuid`) passed to `String` parameters | Added `.toString()` and `Settings.getAssistantById(String)` |
| `38122476111` | `:app:compile*DebugKotlin` | Missing imports for `MemorySummaryCadence` & `MemorySummaryService` | Explicit imports added across model, VM, and DI |
| `38123308058` | `:app:compile*DebugKotlin` | `ViewModelModule` type inference fail due to unresolved VM params | Explicit type parameters & imports added |
| `38124911380` | `:app:compileStableDebugKotlin` | `OOMErrorException: GC overhead limit exceeded` | Raised `kotlin.daemon.jvmargs` from 2GB to 4GB with `UseParallelGC` |
| `38125894286` | `Configure swap` step | `fallocate failed: Text file busy` | Removed redundant swap step (runner already has swap active) |
| `38125997809` | Artifact Packaging | `path: **/*.apk` created an 845 MB zip with 8 APKs | Constrained build task and artifact to single tablet `arm64-v8a` APK |
| `38128221859` | App Runtime 401 | `MemorySummaryService` called models with unhydrated API keys | Hydrated provider secrets via `secretKeyManager.populateProviderSecrets` |
| `38131998528` | In-place app upgrade failed | Ephemeral CI runners generate random debug certificates each run | Embedded persistent base64 debug keystore in CI workflow |
| `38134248709` | Runtime AskOrUpdate parse error | Rigid parser failed on non-standard LLM output shapes | Built tolerant multi-stage parser & switched to offline keyword search |
| `38136735025` | UI Format Disruption | LLM output string array for `dive_deeper` & raw JSON fell through to content | Built 6-stage parser with string array support, unescaped newline escaping, and auto-healing |
| `40122405532` | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Attempted to upgrade official v1.4.8 release signed with developer's key | Assigned unique `applicationId: "lastchat.rikkafork.velvet"` so fork coexists safely |
| `40124377842` | APK bloat (~106 MB) and UI lag | Dev debug flavor lacked R8 minification and resource shrinking | Created `build-stable.yml` running `:app:assembleStableRelease` with `-Plastchat.release.minify=true` (reduced to ~66MB / 81MB) |
| `40181722514` | Keystore exposed in public git & model search kicked out by keyboard | Insecure hardcoded keystore in workflow; `isImeVisible` auto-dismissing `showPicker` | Moved keystore to GitHub Secret `SIGNING_KEYSTORE_BASE64`; bound picker dismissal to composer `isFocused`; paginated memories 10/page |
| `[PENDING]` | Provider model fetch UI hang | Under investigation (suspect network timeout in `listModels` or recomposition storm in provider settings) | Next session diagnostic checklist documented in Section 9 |
