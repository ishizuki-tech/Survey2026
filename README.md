# Survey2026 (SurveyApp)

**Survey2026** is an Android survey runtime designed for field use where connectivity may be unreliable or unavailable. It combines structured survey execution, on-device speech recognition, local SLM-based answer evaluation/follow-up generation, persistent diagnostics, and resilient background upload/recovery.

The Android Studio project name is `SurveyNav`; the product/repository name is **Survey2026**.

> Maintainer: [ishizuki.tech@gmail.com](mailto:ishizuki.tech@gmail.com)

---

## Current baseline

Stable published baseline:

- Main source: `92de06de2dec83c78aa41f240d6c224a95d84af1` (`92de06d`)
- Published release: `build-87-92de06d`
- Pixel 9a / Android 16 field validation: PASS for the completed upload/recovery scenarios recorded in [ROADMAP.md](ROADMAP.md)
- APK and signing-certificate provenance are published for release #87
- `whisper.cpp` baseline: v1.9.3
- LiteRT-LM: 0.16.1

For current completed work and remaining priorities:

- [ROADMAP.md](ROADMAP.md) — current baseline, validated behavior, and product direction
- [TODO.md](TODO.md) — concrete unfinished implementation, verification, and documentation work

---

## What the app does

Survey2026 is not a generic chatbot and not just a digital form. It is a structured Android interview runtime that:

1. Loads a validated survey configuration.
2. Runs a deterministic survey/navigation flow.
3. Accepts typed and optional voice input.
4. Uses local Whisper inference for speech-to-text.
5. Uses a local SLM to evaluate free-text answer quality.
6. Generates follow-up questions only when the configured evaluation requires one.
7. Persists survey state and export artifacts.
8. Queues mandatory survey JSON for resilient background upload.
9. Recovers pending survey uploads after restart, reboot, app replacement, or network restoration.

Core survey execution remains usable offline. Network access is used for model/download workflows and configured upload/diagnostic destinations.

---

## Architecture at a glance

```text
Android UI / Navigation
        |
        v
Survey state + configuration
        |
        +--------------------+
        |                    |
        v                    v
Voice capture           Typed answer
        |
        v
whisper.cpp JNI
        |
        v
Transcribed answer
        |
        +-----------> Local SLM evaluation
                         |
                         +--> accepted answer
                         |
                         +--> follow-up generation when required

Survey Finish
    |
    v
SurveyUploadFinalizer
    |
    v
SurveyUploadWork.reconcile(...)
    |
    +--> WorkManager submission / adoption
    +--> logical-survey duplicate protection
    +--> tracker / uploaded-state checks

Recovery entry points
    |
    +--> normal app startup
    +--> BOOT_COMPLETED
    +--> MY_PACKAGE_REPLACED
    +--> network recovery
    |
    v
SurveyUploadRescheduler.recoverPendingSurveyUploads(...)
    |
    v
PendingSurveyUploads discovery
    |
    v
SurveyUploadWork.reconcile(...)
```

The current upload/recovery design deliberately uses one shared reconciliation path. The old direct `reenqueuePendingSurveyUploads()` path is not part of the current design.

---

## Repository layout

```text
.
├── app/                         Android application module
├── nativelib/                   whisper.cpp JNI Android library
├── whisper.cpp/                 pinned Git submodule
├── scripts/                     helper/automation scripts
├── .github/workflows/           CI, release, and branch-preview workflows
├── ROADMAP.md                   validated baseline + roadmap
└── TODO.md                      unfinished concrete work
```

Important implementation areas:

- `app/src/main/kotlin/com/negi/survey/SurveyApp.kt`
  - process/application bootstrap
  - startup upload recovery coordination
- `app/src/main/kotlin/com/negi/survey/net/PendingSurveyUploads.kt`
  - grouped pending-survey discovery
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadFinalizer.kt`
  - finalization handoff into the shared upload path
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadRescheduler.kt`
  - recovery orchestration
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadWork.kt`
  - WorkManager reconciliation and logical-survey identity handling
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadWorkTracker.kt`
  - exact work tracking
- `app/src/main/kotlin/com/negi/survey/net/UploadRescheduleReceiver.kt`
  - reboot / package-replacement recovery entry point
- `app/src/main/kotlin/com/negi/survey/slm/AiRepository.kt`
  - local SLM orchestration
- `app/src/main/kotlin/com/negi/survey/slm/LiteRtLM.kt`
  - LiteRT-LM runtime wrapper
- `nativelib/src/main/jni/whisper/`
  - JNI bridge and native whisper.cpp build

---

## Current toolchain

| Component | Current repo value |
| --- | --- |
| AGP | 9.3.2 |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.08.00 |
| Java | 17 |
| compileSdk | 37 |
| targetSdk | 36 |
| minSdk | 26 |
| NDK | 29.0.14206865 |
| CMake | 3.22.1 |
| LiteRT-LM | 0.16.1 |
| Native Android ABI | arm64-v8a |

AGP 9 built-in Kotlin is used. Do not add `org.jetbrains.kotlin.android` to the Android modules unless the build design changes.

---

## Getting started

### Clone with submodules

```bash
git clone --recurse-submodules https://github.com/ishizuki-tech/Survey2026.git
cd Survey2026
```

For an existing checkout:

```bash
git submodule update --init --recursive
```

### Open in Android Studio

Open the repository root and allow Gradle Sync to complete.

A physical Android device is recommended because the project depends on real microphone, JNI, on-device inference, WorkManager, and lifecycle behavior that are not fully represented by emulator-only testing.

### Safe local build without embedded development secrets

```bash
./gradlew :app:testDebugUnitTest --no-daemon \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false

./gradlew :app:assembleDebug --no-daemon \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false
```

Release assembly:

```bash
./gradlew :app:assembleRelease --no-daemon \
  -PskipModelDownload=true \
  -Prelease.allowSecrets=false
```

The normal Gradle `release` build is **not forcibly debug-signed**. `release.useDebugSigning=true` remains an explicit opt-in only. Production release signing is handled separately.

---

## Survey configuration

Shipped survey configurations:

- `app/src/main/assets/survey_config10.yaml` — English
- `app/src/main/assets/survey_config_sw_10.yaml` — Swahili

The configuration drives the structured survey and the SLM prompt behavior.

### TWO_STEP answer validation

The shipped Q8-Q17 English and Swahili nodes use the two-step path where configured:

1. **EVAL** — evaluate the respondent answer.
2. Parse strict structured output:
   - `score`
   - `missing_points`
   - `followup_needed`
3. If needed and valid, run **FOLLOWUP** generation.
4. Persist only an accepted, non-duplicate follow-up question.
5. Keep run/survey ownership so cancelled or stale inference cannot update a replacement chain.

Malformed or contradictory evaluation output fails closed.

ONE_STEP support remains available for configurations that use it, but it is not the active Q8-Q17 baseline.

Real-model semantic quality is intentionally tracked separately from deterministic app correctness. See [TODO.md](TODO.md).

---

## On-device SLM

Survey2026 uses LiteRT-LM for local model inference.

Current dependency:

```text
com.google.ai.edge.litertlm:litertlm-android:0.16.1
```

The app-side SLM layer is responsible for:

- streaming generation
- cancellation
- run/survey ownership
- terminal-result isolation
- evaluation JSON parsing
- follow-up generation
- warmup/readiness handling
- timeout/recovery behavior

The model engine may behave differently across hardware/backend implementations; deterministic app guards are therefore kept separate from semantic model acceptance testing.

---

## Whisper / native speech

The native library module is `nativelib`.

Current CMake entry point:

```text
nativelib/src/main/jni/whisper/CMakeLists.txt
```

Current Gradle native configuration:

- NDK `29.0.14206865`
- CMake `3.22.1`
- `arm64-v8a` only
- Java/Kotlin target 17
- native optimization flags include `-O2`
- CPU-only JNI build

The native build explicitly forces these GGML backends off:

- CUDA
- Metal
- OpenCL
- Vulkan
- HIP
- SYCL
- BLAS
- RPC

`WHISPER_DIR` is supported as an optional CMake cache path. If it is not supplied, the CMake file searches known repository-relative locations, including the root `whisper.cpp` checkout.

---

## Permissions and platform behavior

The current manifest declares:

- `RECORD_AUDIO`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `RECEIVE_BOOT_COMPLETED`
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- legacy `READ_EXTERNAL_STORAGE` through API 32
- legacy `WRITE_EXTERNAL_STORAGE` through API 28

The microphone feature is declared optional at the device-capability level.

The final product behavior when microphone permission is denied is still an explicit P0 product decision; see [TODO.md](TODO.md).

---

## Survey upload and recovery

Survey JSON is the mandatory survey artifact.

The current design uses logical survey identity rather than only file-path identity. The recovery layer:

- groups pending artifacts
- selects a canonical candidate
- reconciles against WorkManager state
- checks tracker state
- checks uploaded state
- suppresses duplicate logical submissions
- preserves retry/recovery semantics

Current recovery classifications include:

- `SUBMITTED`
- `ACTIVE`
- `SUBMITTED_TRACKER_UNCONFIRMED`
- `ALREADY_UPLOADED`
- `DEFERRED`
- `INVALID`
- `OPERATIONAL_FAILURE`

Validated on Pixel 9a / Android 16:

- offline pending recovery
- network restore -> upload
- reboot recovery
- app-update / `MY_PACKAGE_REPLACED` recovery
- duplicate suppression
- post-success cleanup / no re-submit

Direct app-side `LOCKED_BOOT_COMPLETED` logging remains a validation observation point rather than a claimed completed result.

---

## Diagnostics and logging

The repository contains persistent runtime/crash logging and background diagnostic-upload support, including components such as:

- `CrashCapture`
- `RuntimeLogStore`
- `AppRingLogStore`
- `GitHubUploadWorker`
- `GitHubUploader`
- `GitHubDiagnosticsConfigStore`

Survey upload recovery and generic diagnostic upload are separate concerns. A diagnostic upload failure must not be treated as evidence that mandatory survey JSON recovery failed.

Exact retention, operator access, storage layout, and diagnostic-upload policy remain documentation work tracked in [TODO.md](TODO.md).

---

## CI, branch previews, and releases

### `.github/workflows/BranchBuild.yml`

Runs for non-`main`, non-`gh-pages` branch pushes and manual dispatch.

It:

- runs JVM unit tests
- builds a debug APK
- checks the APK for a plaintext `hf_` token marker
- publishes a branch-specific APK artifact
- publishes branch preview metadata to `gh-pages`

Branch preview APKs are development builds and are separate from production releases.

### `.github/workflows/AndroidBuild.yml`

Workflow name: **Android Release APK**.

Current pinned CI toolchain:

- compile SDK 37
- Build Tools 36.0.0
- NDK 29.0.14206865
- CMake 3.22.1
- JDK 17

The workflow runs for `main` pushes and manual dispatch. Its current publication logic enables production release publication for `main` and for manual dispatch when `publish_release=true`.

Production signing uses GitHub Actions secrets and the dedicated release certificate. The Gradle project itself does not hard-code the release keystore.

Published release metadata includes APK/signing provenance; config-hash and additional download-page consistency work is tracked in [TODO.md](TODO.md).

---

## Secret handling

Do not commit:

- release keystores
- keystore passwords
- GitHub tokens
- Hugging Face tokens
- other credentials
- generated signed APKs unless intentionally published as release artifacts

Debug builds can embed development credentials only when explicitly enabled.

The Hugging Face token transport path uses AES-GCM material rather than storing the plaintext token directly in BuildConfig. Because decryption material is also delivered with the application, this is **APK-obfuscation / plaintext-avoidance**, not a secure secret-storage boundary against a determined APK analyst.

Release builds only allow embedded runtime credentials when `release.allowSecrets=true`.

---

## Release signing

Expected local signing report after the release-signing cleanup:

```text
Variant: debug
Config: debug

Variant: release
Config: none
```

This is intentional.

- Debug APK -> Android debug key
- Unsigned local release APK -> external/manual signing when needed
- Production release -> dedicated release key in the release workflow

The production certificate used for release #87 was also verified with a local release-signed update on Pixel 9a.

---

## Testing strategy

Current layers include:

- JVM unit tests for deterministic logic
- Android instrumentation coverage for selected flows
- branch CI build/test
- real-device validation on Pixel 9a
- release assembly/signing validation
- separate real-model semantic evaluation

A passing JVM test suite does not prove model-language quality, and a successful diagnostic upload is not a substitute for survey-upload acceptance.

See [ROADMAP.md](ROADMAP.md) for validated areas and [TODO.md](TODO.md) for pending benchmark/acceptance work.

---

## Troubleshooting

### Submodule missing

```bash
git submodule update --init --recursive
```

### NDK/CMake mismatch

Use the repository-pinned versions:

```text
NDK 29.0.14206865
CMake 3.22.1
```

If native configuration is stale, close active builds, then remove only the generated native cache and rebuild:

```bash
rm -rf nativelib/.cxx
```

### Release unexpectedly uses the debug certificate

Check:

```bash
./gradlew :app:signingReport
```

Expected:

```text
Variant: release
Config: none
```

Also verify that no global or local Gradle property enables:

```text
release.useDebugSigning=true
```

### Survey upload appears stuck

Check the survey-specific recovery/reconciliation logs first. Do not infer survey failure only from generic diagnostic worker errors.

Relevant components:

- `PendingSurveyUploads`
- `SurveyUploadRescheduler`
- `SurveyUploadWork`
- `SurveyUploadWorkTracker`
- `UploadRescheduleReceiver`

---

## License

MIT License — see [LICENSE](LICENSE).
