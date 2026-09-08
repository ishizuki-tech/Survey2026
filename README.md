# Survey2026

Survey2026 is an offline-first Android survey application. It is written in Kotlin with Jetpack Compose, uses LiteRT-LM for on-device language-model validation, and uses whisper.cpp for on-device speech-to-text through an Android NDK/JNI/CMake library.

## Features

- Config-driven survey navigation, including text, choice, AI, review, and completion nodes.
- Two shipped survey configurations: English and Swahili.
- On-device speech capture and transcription through whisper.cpp.
- On-device AI validation of free-text survey answers, including bounded respondent follow-up questions.
- Local diagnostics, runtime logging, and export/upload support.

## Survey configurations

The app discovers YAML survey assets and strictly validates the selected configuration before starting a survey. The currently shipped configurations are:

- `app/src/main/assets/survey_config10.yaml` — English
- `app/src/main/assets/survey_config_sw_10.yaml` — Swahili

Both define the current survey graph, including AI nodes Q8 through Q17, plus model settings and prompt templates. The configuration picker labels these assets as English and Swahili.

## AI follow-up validation

The shipped Q8–Q17 prompts are one-step (`ONE_STEP`) validation prompts. A respondent's main answer is evaluated first; a sufficient result completes the turn and enables **Next**. An insufficient result can lead to a follow-up question:

```text
MAIN answer
  -> ONE_STEP validation
  -> sufficient: Next
  -> insufficient: Follow-up 1
  -> accumulated revalidation
  -> sufficient: Next
  -> insufficient: Follow-up 2
  -> final accumulated validation
  -> Next
```

The policy is deliberately bounded:

- At most two respondent follow-up questions can be stored for an AI node; there is no FU3.
- The accumulated revalidation prompt retains the original main answer and appends answered follow-up question/answer pairs in order. Unanswered follow-ups are excluded.
- A candidate follow-up must be distinct from earlier questions after normalization, and capacity must remain.
- A normally completed low-score one-step result with no usable follow-up can receive one consistency-repair follow-up attempt. Repair is not eligible after timeout or error, is disabled at zero capacity, and does not recurse.
- **Next** stays disabled until the AI turn is terminal, no follow-up remains unanswered, and model/speech submission work is no longer active.

The codebase also retains configurable two-step infrastructure: a node is treated as `TWO_STEP` only when it has both evaluation and follow-up prompt templates. The currently shipped Q8–Q17 configurations provide one-step prompts, not two-step prompt pairs.

## Architecture

- `SurveyViewModel` owns survey answers, follow-up entries, navigation state, and prompt rendering.
- `AiViewModel` owns AI conversation state, streamed model steps, and validation lifecycle state.
- `AiScreen` binds the survey and AI state to the Compose UI, follow-up policy, speech draft handling, and navigation gating.
- `app/src/main/kotlin/com/negi/survey/slm/` contains LiteRT-LM integration, repository orchestration, parsing, and native lifecycle coordination.
- `nativelib/` builds the whisper.cpp JNI library with the Android NDK and CMake; the current native ABI target is `arm64-v8a`.

## Repository layout

```text
app/                         Android application, Compose UI, survey/config, AI, speech, diagnostics
app/src/main/assets/         Shipped survey YAML configurations
nativelib/                   whisper.cpp Android JNI/CMake library
whisper.cpp/                 whisper.cpp Git submodule
.github/workflows/           Android release and download-page workflow
scripts/                     Project helper scripts
```

## Requirements

- Android Studio and an Android SDK
- JDK 17
- Android NDK `29.0.14206865` and CMake `3.22.1` for the native module
- An `arm64-v8a` Android device for the current native ABI build

## Getting started

Clone with the whisper.cpp submodule:

```bash
git clone --recurse-submodules https://github.com/ishizuki-tech/Survey2026.git
cd Survey2026
```

For an existing checkout with a missing submodule:

```bash
git submodule sync --recursive
git submodule update --init --recursive
```

Open the repository in Android Studio, let Gradle sync, choose a device, and run the `app` configuration.

## Build and test

Useful local commands:

```bash
./gradlew :app:assembleDebug

./gradlew :app:testDebugUnitTest

./gradlew :app:compileDebugAndroidTestKotlin
```

The project includes unit tests for configuration navigation, follow-up iteration and repair policy, prompt/navigation behavior, and LiteRT native lifecycle coordination. Android instrumentation coverage includes follow-up persistence and repair chains, AI ViewModel/config prompt coverage, speech/UI behavior, and LiteRT repository behavior.

`RealLiteRtAiFollowupFlowInstrumentationTest` provides real LiteRT/Gemma device coverage for the Q8 iterative follow-up flow. It includes a single-run safety-invariants test and a separately invoked configurable soak test; these are not implied to run automatically in every CI build.

## On-device AI

### LiteRT-LM

LiteRT-LM runs the survey validation model on device. The integration is in `app/src/main/kotlin/com/negi/survey/slm/`, with AI UI state in `AiViewModel` and prompt construction in `SurveyViewModel`. The current survey uses compact one-step validation for Q8–Q17; the generic two-step path remains available only for configurations that supply both required prompt types.

### Whisper

Speech capture and transcription are handled locally through the whisper.cpp integration. Kotlin speech/controller code is in `app/src/main/kotlin/com/negi/survey/whisper/` and `app/src/main/kotlin/com/negi/survey/vm/WhisperSpeechController.kt`; the JNI/CMake build is in `nativelib/`.

## Release and download

The `Android Release APK` GitHub Actions workflow runs on pushes to `main` and can be started manually with the `publish_release` input.

### Build-only CI

With `publish_release=false`, the workflow prepares the Android environment, runs lint, builds the release APK, verifies and collects it, and uploads the APK as a workflow artifact. It does not publish a GitHub Release or GitHub Pages download page.

### Published release

With `publish_release=true`, the workflow requires its release configuration, aligns and signs the final APK, verifies its signature and certificate SHA-256, publishes or updates the GitHub Release, and publishes the mobile download page.

The download page is available at:

<https://ishizuki-tech.github.io/Survey2026/>

It provides the APK download, release/build metadata, QR code, and links for the YAML configurations included in that APK. Each config offers:

- **View in browser** — a GitHub blob link pinned to the exact workflow commit.
- **Download** — the GitHub Release asset pinned to the release tag.

### APK config integrity

For a published release, the workflow extracts `assets/survey_config10.yaml` and `assets/survey_config_sw_10.yaml` from the final APK. It fails if either extracted asset is missing or empty, then compares each extracted file byte-for-byte with its source asset. The extracted YAML files are published as GitHub Release assets.

This makes the release-page YAML downloads correspond to the exact APK being published, while the browser-view links point at the exact source commit used for that build.

## Diagnostics

The intro screen always shows a `Build: <timestamp>` stamp generated during Gradle configuration. Include that value when reporting a build issue. Runtime logs and crash-related diagnostics are stored by the app's diagnostics components and can be used for support investigation.

## Security

- Do not commit keystores, tokens, API keys, or other secrets.
- Provide release signing material through GitHub Actions secrets; do not place it in the repository.
- Treat model outputs, survey responses, and diagnostic exports as potentially sensitive data when sharing logs.

## Troubleshooting

- If native compilation fails, confirm the configured Android SDK, NDK, CMake, and `whisper.cpp` submodule are present.
- If the submodule is missing, run the submodule commands in [Getting started](#getting-started).
- For release failures, check the workflow's APK signature, config-integrity, and release-asset steps before changing application code.

## License

This project is licensed under the [MIT License](LICENSE).
