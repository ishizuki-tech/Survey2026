# Survey2026

**Survey2026** is an Android (Kotlin) + NDK (C/CMake) project aimed at building an **offline-first survey app** with **on-device speech-to-text** via **whisper.cpp**.

This repo is intentionally structured to keep the Android app layer clean while isolating native inference code in a dedicated module.

> Status note: This README is written to be *implementation-friendly* even while the code is evolving. Sections marked **TODO** should be updated as the project crystallizes.

---

## Table of contents

* [What this project is](#what-this-project-is)
* [Key capabilities](#key-capabilities)
* [Repository layout](#repository-layout)
* [Build prerequisites](#build-prerequisites)
* [Getting started](#getting-started)
* [Native build (NDK/CMake) details](#native-build-ndkcmake-details)
* [Model files (Whisper)](#model-files-whisper)
* [Run-time permissions and I/O](#run-time-permissions-and-io)
* [Troubleshooting](#troubleshooting)
* [CI / workflows](#ci--workflows)
* [Security and repo hygiene](#security-and-repo-hygiene)
* [License](#license)

---

## What this project is

This repository is a **multi-module Android Studio project**:

* `app/` contains the Android application (UI, navigation, storage, orchestration).
* `nativelib/` contains NDK + JNI code to build and expose native functionality.
* `whisper.cpp/` is included as a **Git submodule**.

The design goal is to enable **fast iteration** on native inference while keeping the Kotlin side stable and testable.

---

## Key capabilities

* **Offline voice capture** (microphone) and local file persistence.
* **On-device speech-to-text** powered by `whisper.cpp` (native library).
* **Multi-ABI builds** (typical Android ABIs; exact set depends on Gradle/CMake config).
* **Repository hygiene**:

    * model files (`*.bin`, `*.gguf`) and large media are ignored by default
    * keystores and Google services config are ignored

**TODO:** list actual supported languages / models and current UI flow.

---

## Repository layout

Top-level structure (high-level):

* `.github/workflows/` — CI workflows (Gradle build/test, etc.)
* `app/` — Android application module
* `nativelib/` — Android library module that builds native code via CMake
* `scripts/` — helper scripts (model download/build helpers)
* `whisper.cpp/` — whisper.cpp submodule (pinned to a specific commit)
* `images/` — documentation images/screenshots

---

## Build prerequisites

### Required

* **Android Studio** (latest stable recommended)
* **Android SDK** (installed via Android Studio)
* **NDK (Side by side)** (installed via Android Studio)
* **CMake** (installed via Android Studio)

### Install NDK/CMake via Android Studio

1. `Tools` → `SDK Manager`
2. `SDK Tools` tab
3. Install:

    * `NDK (Side by side)`
    * `CMake`

> If you see `NDK is not installed`, this is the first thing to fix.

---

## Getting started

### 1) Clone with submodules

This project depends on a Git submodule (`whisper.cpp`). Clone with submodules enabled:

```bash
git clone --recurse-submodules https://github.com/ishizuki-tech/Survey2026.git
cd Survey2026
```

Already cloned without submodules?

```bash
git submodule update --init --recursive
```

### 2) Open in Android Studio

* Android Studio → **Open** → select the `Survey2026` directory
* Allow Gradle sync to complete

### 3) Build and run

* Select a device (physical device recommended for audio + performance)
* `Run` the `app` configuration

Optional CLI build:

```bash
./gradlew :app:assembleDebug
```

---

## Native build (NDK/CMake) details

### Where native build lives

* The native build is owned by the `nativelib` module.
* CMake entry points are typically under:

    * `nativelib/src/main/jni/**/CMakeLists.txt`

> Exact paths may evolve; search for `CMakeLists.txt` inside `nativelib/` if reorganized.

### How CMake finds whisper.cpp

`whisper.cpp` is included as a **submodule** at the repo root (`/whisper.cpp`).

**Recommended practice:** keep a *single canonical location* for whisper.cpp and avoid duplicate copies.

If you need to make the project resilient across environments, do it explicitly:

* Prefer a single CMake cache variable (example name):

    * `WHISPER_CPP_DIR=/absolute/or/repo-relative/path/to/whisper.cpp`

Then pass it from Gradle:

* In `nativelib`’s `build.gradle(.kts)`, via:

    * `externalNativeBuild { cmake { arguments += listOf("-DWHISPER_CPP_DIR=..." ) } }`

**TODO:** document the exact variable name used in this repo once finalized.

### ABI notes

Android native builds typically target a subset of:

* `arm64-v8a` (recommended baseline)
* `armeabi-v7a` (legacy)
* `x86_64` (emulators)

To reduce build time, restrict ABIs in Gradle (example):

```gradle
android {
  defaultConfig {
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }
}
```

**TODO:** align this section with the actual ABI filters used in the repo.

### Typical NDK/CMake debugging workflow

When native build gets weird:

1. **Clean CMake cache**

    * Delete module-level `.cxx/` folders (or use Android Studio: `Build → Clean Project`)
2. Re-sync Gradle
3. Rebuild

Native build artifacts are usually under:

* `nativelib/.cxx/` (CMake/Ninja intermediates)

---

## Model files (Whisper)

### Why models are not in Git

Whisper models are large and frequently change. This repository intentionally ignores:

* `*.bin`
* `*.gguf`

…and other large media (see `.gitignore`).

### Recommended model placement

Pick a **single consistent location** and make the Kotlin and native layers agree.

Common choices:

1. `app/src/main/assets/models/` (bundled into APK)

* ✅ easy distribution
* ❌ increases APK size

2. App-internal storage (download on first run)

* ✅ keeps APK small
* ✅ supports multiple models

3. External storage (developer convenience)

* ✅ easy to swap while developing
* ❌ permissions / scoped storage concerns

**Recommended for production:** (2) download into internal storage.

### Suggested directory layout (example)

```text
<app-internal-files>/models/
  - whisper-small.bin
  - whisper-large-v3-turbo.gguf
  - README.txt
```

**TODO:** document the actual model naming and loader code expectations.

---

## Run-time permissions and I/O

### Microphone permissions

At runtime, the app must request:

* `RECORD_AUDIO`

Depending on storage strategy, you may also need storage/media permissions.

### Audio pipeline (typical)

A common flow is:

1. Capture PCM audio via Android APIs
2. Persist as WAV (or raw PCM)
3. Feed file/bytes into native whisper.cpp wrapper
4. Return text + timing info

**TODO:** document actual file formats used by the app.

---

## Troubleshooting

### `NDK is not installed`

Fix:

* Android Studio → `Tools → SDK Manager → SDK Tools`
* Install `NDK (Side by side)`

### CMake/Ninja fails immediately

Common causes:

* missing CMake install
* stale `.cxx/` build cache
* ABI mismatch

Fix checklist:

* install CMake in SDK Manager
* `Build → Clean Project` then rebuild
* delete `nativelib/.cxx/` (as last resort)

### whisper.cpp not found / headers missing

This almost always means submodules aren’t initialized:

```bash
git submodule update --init --recursive
```

Then re-sync Gradle and rebuild.

### “It builds on my machine” syndrome

Native builds are sensitive to:

* NDK version differences
* host OS toolchain differences
* ABI filters / CMake options

Best practice:

* pin NDK version in documentation
* keep CMake arguments explicit
* avoid multiple whisper.cpp directories

---

## CI / workflows

This repository includes GitHub Actions workflows under `.github/workflows/`.

Typical CI goals:

* `./gradlew assembleDebug`
* `./gradlew test`
* (optional) build release artifacts

**TODO:** list exact workflow names and what each does.

---

## Security and repo hygiene

This repo is set up to avoid common Android accidents:

* **Do not commit keystores** (`*.jks`) — ignored by default
* **Do not commit `google-services.json`** — ignored by default
* **Do not commit model files** (`*.bin`, `*.gguf`) — ignored by default

If you need reproducible builds:

* use scripts to fetch models from a trusted location
* verify hashes before use

---

## License

MIT License — see `LICENSE`.

---

## Roadmap (optional)

**TODO:** add a short roadmap once milestones are defined, e.g.

* [ ] Stable native JNI API surface
* [ ] Model download + verification
* [ ] End-to-end transcription UI
* [ ] Benchmark harness (device + model + latency/memory)
* [ ] Structured export format for survey + audio + transcript
