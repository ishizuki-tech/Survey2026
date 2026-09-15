# Survey2026 TODO

This file tracks unfinished implementation, verification, and documentation work. Items remain unchecked until their implementation and evidence are independently verified.

## Build Toolchain

- [ ] Pin and document the Android NDK version in both Android modules and CI.
- [ ] Document the actual CMake version and production ABI configuration.

## LiteRT-LM / SLM

- [ ] Document prompt resolver APIs, including the active ONE_STEP and TWO_STEP paths and enforced output schemas.
- [ ] Document model-run isolation, cancellation, terminal-result invariants, and context/token capacity behavior.
- [ ] Document LiteRT-LM model loading, storage, discovery, and integrity behavior.

## Whisper / Native Audio

- [ ] Document the exact CMake integration with `whisper.cpp`.
- [ ] Document audio format, WAV/raw behavior, and JNI transcription ownership/threading.
- [ ] Document Whisper model location, naming, discovery, and integrity behavior.

## Android Permissions and Storage

- [ ] Document manifest and runtime permissions, especially microphone denial behavior.
- [ ] Document application storage for survey state, recordings, models, runtime logs, and crash diagnostics.

## Diagnostics and Logging

- [ ] Document log locations, retention, crash capture, and diagnostic upload behavior.
- [ ] Document diagnostic configuration handling without exposing secrets.

## Survey Configuration

- [ ] Document the SurveyConfig schema, validation rules, selection flow, and new-language/config process.
- [ ] Verify and document ONE_STEP versus TWO_STEP configuration semantics against the active runtime and shipped assets.

## AI Follow-up Validation

- [ ] Document the iterative MAIN/FU1/FU2/terminal state machine and accumulated prompt construction.
- [ ] Document duplicate-follow-up normalization and bounded consistency repair.
- [ ] Document real-device model-output edge cases, including low-score empty follow-ups and duplicate candidates.

## Testing

- [ ] Document JVM and instrumentation test coverage and requirements.
- [ ] Document real LiteRT/Gemma safety and soak test execution, including `ITERATIONS` and expected runtime.
- [ ] Decide which real-model tests, if any, run automatically in CI.

## CI and Release

- [ ] Document workflow responsibilities and build-only versus published-release behavior.
- [ ] Document signing, release versioning, APK naming, config integrity, and `latest.json` behavior.

## Release Download Page

- [ ] Document the production download-page URL, APK behavior, and YAML browser/download link provenance.
- [ ] Consider release-note and independently published config-hash presentation.

## Repository Maintenance and Security

- [ ] Keep `whisper.cpp` pinned to a known submodule commit.
- [ ] Periodically verify that README architecture descriptions match the repository.
- [ ] Keep generated outputs and model binaries out of Git unless intentionally distributed.
- [ ] Verify release keystores, API tokens, and other credentials are never committed.

## Documentation Policy

- [ ] Keep README limited to current, verified behavior.
- [ ] Keep unfinished work in this file and mark an item complete only after implementation, validation, and documentation agree.
