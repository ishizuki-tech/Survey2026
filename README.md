# Survey2026 (SurveyApp)

**Survey2026** is an Android Studio multi-module project (**Android/Kotlin + NDK/C/CMake**) for an **offline-first survey app** with:

* **On-device speech-to-text** via **whisper.cpp** (NDK/JNI)
* **On-device SLM (Small Language Model) inference** via **LiteRT-LM** (and related on-device inference APIs)

The repo is structured to keep the Android app layer clean while isolating native inference code (whisper.cpp) in a dedicated module, and keeping SLM orchestration/testability on the Kotlin side.

> Maintainer: [ishizuki.tech@gmail.com](mailto:shu.ishizuki@gmail.com)

---

## Table of contents

* [What this project is](#what-this-project-is)
* [Key capabilities](#key-capabilities)
* [Repository layout](#repository-layout)
* [Build prerequisites](#build-prerequisites)
* [Getting started](#getting-started)
* [On-device SLM integration](#on-device-slm-integration)
* [Native build (NDK/CMake) details](#native-build-ndkcmake-details)
* [Model files](#model-files)
* [Run-time permissions and I/O](#run-time-permissions-and-io)
* [Diagnostics & logging (reliability-first)](#diagnostics--logging-reliability-first)
* [CI / workflows](#ci--workflows)
* [Security and repo hygiene](#security-and-repo-hygiene)
* [Troubleshooting](#troubleshooting)
* [License](#license)
* [Roadmap](#roadmap)

---

## What this project is

This repository is a **multi-module Android Studio project**:

* `app/` — Android application module (UI, navigation, storage, orchestration)
* `nativelib/` — Android library module that builds native code via **NDK + CMake** and exposes it via **JNI**
* `whisper.cpp/` — **Git submodule** (pinned to a specific commit)

Design goals:

* **Offline-first**: core features work without network.
* **On-device AI** is a first-class requirement:

    * Whisper STT runs locally via NDK/JNI.
    * SLM inference runs locally (Kotlin orchestration, on-device engine).
* **Reliability-first**: persistent logs + crash capture + optional diagnostic upload.

---

## Key capabilities

### Offline survey app core

* Offline-first survey flow (navigation + state managed in Kotlin)
* Local persistence for runs/drafts (implementation owned by `app/`)

### On-device speech-to-text (Whisper)

* Microphone capture → local persistence → native transcription
* `whisper.cpp` built into an Android native library via `nativelib/`
* JNI boundary kept narrow (Kotlin owns orchestration; native owns inference)

### On-device SLM integration (required)

* On-device SLM inference for:

    * Free-text normalization / intent classification / evaluation
    * Two-step pipelines (e.g., **EVAL → FOLLOWUP** JSON flows)
    * Streaming generation with cancellation/timeout handling

This repo includes or references SLM components such as:

* `AiRepository.kt` — SLM orchestration (streaming, cancellation, logging, prompt hashing, etc.)
* `LiteRtLM` — SLM engine wrapper (LiteRT-LM bindings)
* `SurveyConfig` — prompt/config resolvers (one-step / two-step)

> The SLM path is not optional in this project: it is part of the primary product surface.

### Diagnostics-first (developer reliability)

* Early crash capture during app startup (`CrashCapture`)
* Persistent runtime logging (e.g., `RuntimeLogStore`, ring logs)
* Optional background upload pipeline via WorkManager:

    * `GitHubUploadWorker`, `GitHubUploader`, and config store(s)

---

## Repository layout

High-level layout:

* `.github/workflows/` — GitHub Actions workflows
* `app/` — Android application module
* `nativelib/` — Native/JNI Android library module
* `scripts/` — helper scripts (model download/build helpers)
* `whisper.cpp/` — whisper.cpp submodule
* `images/` — documentation images/screenshots

Some Details: 

* `MainActivity.kt` — app entry, permissions, UI routing
* `SurveyApp.kt` — Application bootstrap (WorkManager + crash capture wiring often lives here)
* `CrashCapture.kt` — crash capture + exit info collection (if present)
* `GitHubUploadWorker.kt` / `GitHubUploader.kt` — diagnostics upload pipeline
* `RuntimeLogStore.kt` / `AppRingLogStore.kt` — persistent logs
* `SurveyViewModel.kt` — survey state + prompt resolution integration
* `AiRepository.kt` — SLM streaming orchestration + guards

---

## Build prerequisites

### Required

* Android Studio (latest stable recommended)
* Android SDK (via Android Studio)
* NDK (Side by side)
* CMake

Install NDK/CMake:

1. `Tools` → `SDK Manager`
2. `SDK Tools`
3. Install:

    * `NDK (Side by side)`
    * `CMake`

> If you see `NDK is not installed`, fix this first.

**TODO (pin toolchain):**

* Prefer pinning `ndkVersion` and documenting the CMake version.

    * Owner: `app/build.gradle(.kts)` and `nativelib/build.gradle(.kts)`

---

## Getting started

### 1) Clone with submodules

This repo depends on a submodule (`whisper.cpp`). Clone with submodules enabled:

```bash
git clone --recurse-submodules https://github.com/ishizuki-tech/Survey2026.git
cd Survey2026
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

### 2) Open in Android Studio

* Android Studio → **Open** → select the `Survey2026` directory
* Let Gradle Sync complete

### 3) Build & run

* Choose a device (physical device recommended for audio + on-device inference)
* Run the `app` configuration

CLI build:

```bash
./gradlew :app:assembleDebug
```

---

## On-device SLM integration

### Overview

SLM inference is a core feature in Survey2026.

Common responsibilities of the SLM layer:

* Streaming generation (token/segment streaming)
* Termination handling (exactly-one terminal outcome per run)
* Cancellation/teardown safety (no late callbacks updating next run)
* Two-step JSON pipelines (EVAL → FOLLOWUP)
* Near-context-limit behavior (truncation, end-of-turn loops)
* Warmup/readiness (cold start / GPU warmup delays)

The implementation is designed to be **defensive** because on-device engines can have:

* long warmups
* delayed terminal callbacks
* late events after cancel/close
* edge cases when prompts approach context limits

### Where it lives in code

Search for these in `app/`:

* `AiRepository.kt`
* `LiteRtLM` (engine wrapper)
* `SurveyConfig` prompt resolvers

### Prompting patterns

This repo supports (or is designed to support) both:

* **One-step prompts** (single completion)
* **Two-step prompts**:

    * Step 1: EVAL (strict JSON)
    * Step 2: FOLLOWUP (guided by EVAL output)

**TODO (align with implementation):**

* Document the exact prompt resolver APIs and JSON schema enforced.

    * Owner: `SurveyViewModel.kt` + `SurveyConfig` definitions

### Streaming + termination contract (recommended invariants)

Client-side invariants that the app enforces (or should enforce):

* Exactly one terminal callback per run (`done` or `error`)
* No state updates after cancellation/close
* No cross-run contamination (previous callbacks must not affect next run)

**TODO (align with implementation):**

* Document the run ID / session ID strategy used for guarding streams.

    * Owner: `AiRepository.kt` + ViewModel(s)

---

## Native build (NDK/CMake) details

### Where native build lives

* The native build is owned by `nativelib/`.
* Find the CMake entry point:

```bash
# macOS / Linux
find nativelib -name CMakeLists.txt -print
```

Typical (but not guaranteed) location:

* `nativelib/src/main/cpp/CMakeLists.txt`

### How CMake finds whisper.cpp

`whisper.cpp` is included as a **submodule** at repo root:

* `/whisper.cpp`

Recommended practice:

* Keep one canonical whisper.cpp directory.
* Pass its path explicitly into CMake via a single variable.

Example pattern:

* `-DWHISPER_CPP_DIR=/absolute/or/repo-relative/path/to/whisper.cpp`

Gradle wiring (example):

* `nativelib/build.gradle(.kts)`:

    * `externalNativeBuild { cmake { arguments += listOf("-DWHISPER_CPP_DIR=...") } }`

**TODO (make this exact):**

* Replace the example variable name with what your CMake actually uses.

    * Owner: `nativelib/**/CMakeLists.txt`

### ABI notes

Common targets:

* `arm64-v8a` (recommended baseline)

To reduce build time, restrict ABIs in Gradle (example):

```gradle
android {
  defaultConfig {
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }
}
```

**TODO (align with repo):**

* Document the actual `abiFilters` used.

    * Owner: `app/build.gradle(.kts)` and/or `nativelib/build.gradle(.kts)`

### Native build debugging workflow

When native build gets weird:

1. Clean project (`Build → Clean Project`)
2. If needed, delete CMake cache:

    * `nativelib/.cxx/`
3. Re-sync Gradle
4. Rebuild

---

## Model files

### Why models are not in Git

Models are large and frequently change. This repo intentionally ignores common model extensions:

* Whisper models: `*.bin`, `*.gguf`
* (SLM model extensions depend on the engine/toolchain)

### Recommended model placement

Pick one consistent location and make Kotlin + native agree.

Common choices:

1. Bundle into APK: `app/src/main/assets/models/`

* ✅ easy distribution
* ❌ increases APK size

2. Download into app-internal storage (recommended for production)

* ✅ keeps APK smaller
* ✅ supports multiple models

3. Developer convenience: external storage

* ✅ easy to swap
* ❌ permissions / scoped storage complexity

Suggested internal layout:

```text
<app-internal-files>/models/
  - whisper/<whisper-model-files>
  - slm/<slm-model-files>
  - SHA256SUMS.txt
```

**TODO (align with implementation):**

* Document loader behavior for both Whisper and SLM:

    * assets vs internal storage
    * naming rules and discovery
    * hash verification behavior (if implemented)
    * Owner: model loader Kotlin file(s) + `AiRepository.kt` + JNI wrapper contract

---

## Run-time permissions and I/O

### Microphone permission

The app must request:

* `android.permission.RECORD_AUDIO`

Other permissions depend on storage strategy.

**TODO (make this exact):**

* List exact manifest permissions and runtime request behavior.

    * Owner: `app/src/main/AndroidManifest.xml` + permission request code

### Audio pipeline (typical)

1. Capture PCM audio
2. Persist WAV or raw PCM
3. Feed data/file into native whisper.cpp wrapper
4. Return text (optionally timestamps)

**TODO (align with code):**

* Document sample rate / channels / format + JNI method signature(s).

    * Owner: Kotlin audio capture + JNI wrapper

---

## Diagnostics & logging (reliability-first)

This repo aims to be **reliability-first**.

### What exists (implementation-driven)

The current codebase references components such as:

* `CrashCapture` — early crash capture and exit info
* `RuntimeLogStore` / `AppRingLogStore` — persistent logs (ring + file)
* WorkManager uploader pipeline:

    * `GitHubUploadWorker`
    * `GitHubUploader`
    * configuration store(s) such as `GitHubDiagnosticsConfigStore`

### Recommended operating model

* Keep diagnostics collection always-on (low overhead).
* Keep uploads strictly opt-in (dev builds, debug menu, or explicit user action).
* Never upload secrets.

**TODO (align with implementation):**

* Document:

    * where logs are stored (files dir paths)
    * retention policy
    * how to trigger an upload (if enabled)
    * where tokens/config live (never commit them)
    * Owner: `SurveyApp.kt` + `net/` uploader code

---

## CI / workflows

Workflows live in:

* `.github/workflows/`

Typical goals:

* `./gradlew assembleDebug`
* `./gradlew test`

Some variants of this repo include workflows like **Android CI & Release** (manual dispatch, version/tag handling, artifact publishing).

**TODO (make this exact):**

* List the exact workflow file names and what they do.

    * Owner: `.github/workflows/*.yml`

---

## Security and repo hygiene

This repo avoids common Android accidents:

* Do not commit keystores (`*.jks`) — ignored
* Do not commit `google-services.json` — ignored
* Do not commit model files — ignored

Reproducible builds:

* Fetch models via scripts from trusted sources
* Verify hashes before use
* Pin toolchains + submodule commits

---

## Troubleshooting

### NDK not installed

* Android Studio → `Tools → SDK Manager → SDK Tools`
* Install `NDK (Side by side)`

### CMake/Ninja fails immediately

Common causes:

* missing CMake
* stale `.cxx/` cache
* ABI mismatch

Fix:

* `Build → Clean Project`
* delete `nativelib/.cxx/` (last resort)
* re-sync and rebuild

### whisper.cpp not found / headers missing

Submodule is not initialized:

```bash
git submodule update --init --recursive
```

### SLM streaming feels “stuck”

Possible causes:

* cold start / GPU warmup delays before first token
* missing/late terminal callbacks
* cancellation races (late callbacks after cancel/close)
* near-context-limit truncation breaking strict JSON

Recommended actions:

* enable persistent logs and review run IDs / prompt hashes
* use conservative timeouts and explicit warmup strategies
* enforce strict run isolation in the client (ignore late events)

---

## License

MIT License — see `LICENSE`.

---