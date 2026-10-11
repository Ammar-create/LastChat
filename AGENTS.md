# AGENTS.md — LastChat Agent Development Playbook & Incident Post-Mortem

This document serves as the persistent technical playbook for any AI agent or developer working on `LastChat`. It documents the hardware constraints, cloud CI/CD architecture, the single-APK packaging rule, and every failure/gotcha encountered during development so mistakes are never repeated.

---

## 1. Environment & Hardware Constraints (Critical)

- **Target Device:** Lenovo Tab M9 (TB310FU), Android 13, ARM64 (`arm64-v8a`), 4GB RAM (~1.2GB free available RAM).
- **Mobile-Only Environment:** The developer has no desktop/PC. All operations run directly from the device inside a sandboxed Linux PRoot environment (`/workspace`).
- **NEVER Build APKs Locally in PRoot:**
  - Running `./gradlew assembleDebug` locally will spawn the Gradle Daemon, Kotlin Compile Daemon, and KSP worker.
  - Memory consumption spikes by 800MB–1.2GB within seconds, dropping free RAM below 500MB.
  - Android's Low Memory Killer (LMK) will aggressively terminate the host process (`excp.rikkahub`), aborting the conversation, dropping ongoing tasks, and freezing the UI.
  - If a Gradle or Java daemon ever gets spawned locally by accident, kill it immediately:
    ```bash
    killall -9 java 2>/dev/null
    ```

---

## 2. Hard Rule: Single APK for Tablet Only (Never All 8)

When creating or modifying any CI/CD workflow, building an APK, or packaging an Android project:
- **Build ONLY the tablet's native architecture (`arm64-v8a`, `stableDebug` flavor).**
- **NEVER build or upload all 8 combinations (`**/*.apk`).**
  - Building both `dev` and `stable` flavors across 3 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus universal APKs produces 8 APKs totaling **850MB+**, wasting bandwidth, runner time, and causing OutOfMemory crashes.
- **Gradle Task:**
  ```bash
  ./gradlew :app:assembleStableDebug -Plastchat.abi.splits=true --no-parallel --stacktrace
  ```
- **Artifact Path in `actions/upload-artifact@v4`:**
  ```yaml
  path: app/build/outputs/apk/stable/debug/*arm64-v8a*.apk
  ```
  This produces a single, fast **~110 MB** download specifically built for the Lenovo Tab M9.

---

## 3. GitHub Actions CI/CD Playbook

### Required Toolchain
1. **Java:** Use JDK 21 (`actions/setup-java@v4`, distribution `temurin`).
2. **Bun is MANDATORY:** The Gradle task `:web:installWebUiDeps` directly executes `bun` in `web-ui`. Always include `oven-sh/setup-bun@v2` before running Gradle.
3. **Node & PNPM:** PNPM v11 (`pnpm/action-setup@v4`) and Node 22 (`actions/setup-node@v4`).
4. **Mock `google-services.json`:** Must include client objects for BOTH application IDs:
   - `lastchat.rikkafork.cocolal` (stable flavor)
   - `lastchat.rikkafork.cocolal.dev` (dev flavor)
   Missing either causes `:app:process*GoogleServices` to fail immediately.

### Runner Memory & Gradle Configuration (`gradle.properties`)
- **Kotlin Daemon Heap:** Always set `kotlin.daemon.jvmargs=-Xmx4096m -XX:+UseParallelGC -Dfile.encoding=UTF-8`.
  - *Gotcha:* Never cap `kotlin.daemon.jvmargs` at 2048m. Kotlin 2.0 with Jetpack Compose IR lowering and KSP symbol processing will throw `OOMErrorException: GC overhead limit exceeded` if capped at 2GB.
- **Gradle Daemon Heap:** Set `org.gradle.jvmargs=-Xmx4096m -XX:+UseParallelGC -Dfile.encoding=UTF-8`.
- **Sequential Execution:** Use `--no-parallel` on 2-vCPU runners to prevent concurrent Kotlin daemons from competing for memory.
- **Runner Swap:** Ubuntu 24.04 runners on GitHub Actions already have `/swapfile` active and mounted. Never run `fallocate` or `mkswap` on `/swapfile` (fails with `Text file busy`).

### Build Duration & Polling Etiquette
- LastChat debug compilation takes **8 to 11 minutes** on GitHub runners.
- **NEVER use short 20-30s tool-loop polling:** Short loops exhaust agent tool turn budgets and token limits rapidly.
- **Rule:** Use `timeout: 180` (3 minutes) in `workspace_shell` and poll GitHub Actions job status internally via Python script in 25-second increments over a 2.5-minute window.

---

## 4. Kotlin & Architecture Gotchas

### Uuid vs String Type Discipline
- In `LastChat`, Assistant IDs in datastore/preferences (`Assistant.id`) are `kotlin.uuid.Uuid`.
- Navigation routes (e.g. `Screen.MemorySummary(assistantId: String)`) and database entities (`MemorySummaryEntity.assistantId`) use `String`.
- Always convert explicitly via `.id.toString()`.
- Provide extension overloads like `Settings.getAssistantById(id: String)` that safely parse `Uuid` to avoid repetitive route parsing.

### Missing DI & ViewModel Imports
- In Koin modules (`ViewModelModule.kt`), unresolved ViewModel dependencies cause generic `Cannot infer type for type parameter 'T'` errors.
- Always import service and entity classes explicitly in ViewModels and DI definitions (`get<MemorySummaryService>()`).

---

## 5. Incident Post-Mortem Log (Branch `feat/memory-summary`)

| Run ID | Failure Point | Root Cause | Permanent Resolution |
|---|---|---|---|
| `38121608321` | `:app:processDevDebugGoogleServices` | `google-services.json` missing on CI runner | Mock Google Services step added to workflow |
| `38121898636` | `:app:processDevDebugGoogleServices` | Mock only contained stable package, missing `.dev` | Added both package clients to mock JSON |
| `38122118792` | `:app:compile*DebugKotlin` | `assistant.id` (`Uuid`) passed to `String` parameters | Added `.toString()` and `Settings.getAssistantById(String)` |
| `38122476111` | `:app:compile*DebugKotlin` | Missing imports for `MemorySummaryCadence` & `MemorySummaryService` | Explicit imports added across model, VM, and DI |
| `38123308058` | `:app:compile*DebugKotlin` | `ViewModelModule` type inference fail due to unresolved VM params | Explicit type parameters & imports added |
| `38124911380` | `:app:compileStableDebugKotlin` | `OOMErrorException: GC overhead limit exceeded` | Raised `kotlin.daemon.jvmargs` from 2GB to 4GB with `UseParallelGC` |
| `38125894286` | `Configure swap` step | `fallocate failed: Text file busy` | Removed redundant swap step (runner already has swap active) |
| `38125997809` | **SUCCESS (10m 22s)** | All fixes verified, artifact generated | Standardized workflow and documented playbook |
