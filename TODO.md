# Survey2026 TODO

This file tracks implementation and documentation tasks that were previously embedded in `README.md`.

The README should describe the current, verified behavior of Survey2026. Unfinished work, verification tasks, and future improvements belong here.

## Build Toolchain

- [ ] Pin and document the Android NDK version.
  - Verify the version used by `app/build.gradle(.kts)`.
  - Verify the version used by `nativelib/build.gradle(.kts)`.
  - Keep local builds and GitHub Actions aligned.
- [x] Pin and document the CMake version.
  - Verify the version expected by the native build.
  - Keep local builds and GitHub Actions aligned.
- [ ] Document the actual ABI configuration.
  - Verify `abiFilters` in the Android and native modules.
  - Document supported device architectures.
  - Confirm whether `arm64-v8a` is the only supported production ABI.

## LiteRT-LM / SLM

- [ ] Document the exact prompt resolver APIs.
  - Identify the current ONE_STEP resolver path.
  - Identify any remaining TWO_STEP infrastructure.
  - Document the output schemas actually enforced by the implementation.
- [ ] Document model-run isolation.
  - Describe how run IDs and context/session IDs prevent stale callbacks from affecting later inference runs.
  - Document cancellation and terminal-result invariants.
- [ ] Document LiteRT-LM model loading.
  - Define where the model is stored.
  - Document asset/internal-storage behavior.
  - Document model discovery and filename rules.
  - Document model integrity/hash verification if implemented.
- [ ] Document context/token capacity behavior.
  - Define the effective prompt/context limits used by the application.
  - Document behavior near the context limit.
  - Document any truncation or capacity safeguards.

## Whisper / Native Audio

- [ ] Document the exact CMake integration with `whisper.cpp`.
  - Identify the actual CMake variable/path used to locate the submodule.
  - Replace generic example paths with the current implementation.
- [ ] Document the audio input format.
  - Sample rate.
  - Channel count.
  - PCM/sample format.
  - WAV/raw PCM behavior.
- [ ] Document the JNI transcription interface.
  - Kotlin entry point.
  - Native method signature.
  - Input/output ownership.
  - Threading and synchronous native-call behavior.
- [ ] Document Whisper model loading.
  - Model location.
  - Naming/discovery rules.
  - Asset vs internal-storage behavior.
  - Hash/integrity checks if present.

## Android Permissions and Storage

- [ ] Document all manifest permissions actually used by the app.
  - Verify `AndroidManifest.xml`.
  - Document which permissions require runtime approval.
- [ ] Document microphone permission behavior.
  - When permission is requested.
  - Behavior when denied.
  - Behavior when permanently denied.
- [ ] Document application storage locations.
  - Survey state/drafts.
  - Audio recordings.
  - Models.
  - Runtime logs.
  - Crash diagnostics.

## Diagnostics and Logging

- [ ] Document persistent log locations.
  - `RuntimeLogStore`.
  - `AppRingLogStore`.
  - Other current diagnostic stores.
- [ ] Document log retention behavior.
  - Maximum size.
  - Rotation behavior.
  - Lifetime/cleanup policy.
- [ ] Document crash capture behavior.
  - Startup crash handling.
  - Process exit information.
  - Persistence behavior.
- [ ] Document diagnostic upload behavior.
  - How uploads are triggered.
  - Which worker/uploader is used.
  - Destination.
  - Retry behavior.
  - Whether upload is opt-in.
- [ ] Document diagnostics credential/config handling.
  - Explain where configuration comes from.
  - Never document or commit actual tokens/secrets.

## Survey Configuration

- [ ] Document the SurveyConfig schema.
  - Node types.
  - Required fields.
  - Navigation fields.
  - AI prompt fields.
  - Validation/output-mode fields.
- [ ] Document config validation rules.
  - Invalid/missing node references.
  - START node requirements.
  - AI node requirements.
  - ONE_STEP vs TWO_STEP requirements.
- [ ] Document how config files are selected at runtime.
- [ ] Document the process for adding a new survey language/config.
  - Asset naming.
  - Config registration/discovery.
  - Tests required before shipping.

## AI Follow-up Validation

- [x] Add developer documentation for the iterative follow-up state machine.
  - MAIN.
  - FU1.
  - FU2.
  - Terminal state.
- [x] Document accumulated prompt construction.
  - Preserve original MAIN answer.
  - Append answered follow-up Q/A pairs in order.
  - Exclude unanswered follow-ups.
- [ ] Document duplicate follow-up normalization rules.
- [x] Document bounded consistency repair.
  - Trigger conditions.
  - Maximum one repair.
  - No recursive repair.
  - No repair when follow-up capacity is exhausted.
- [ ] Document model-output edge cases observed on real devices.
  - Low score with empty follow-up.
  - Out-of-contract scores.
  - Duplicate generated questions.

## Testing

- [x] Document the unit-test suite and intended coverage.
- [ ] Document Android instrumentation test requirements.
  - Physical device vs emulator.
  - Required Android version/device assumptions.
  - Installed-app version-code constraints.
- [ ] Document real LiteRT/Gemma test execution.
  - Safety test.
  - Soak test.
  - `ITERATIONS` instrumentation argument.
  - Expected runtime.
- [ ] Decide which real-model tests, if any, should run automatically in CI.
  - Consider model size.
  - Runtime.
  - Device availability.
  - Flakiness/stochastic inference.

## CI and Release

- [x] Document all GitHub Actions workflow files and responsibilities.
- [ ] Document `publish_release=false`.
  - Build-only behavior.
  - Artifact retention.
  - Signing behavior.
- [ ] Document `publish_release=true`.
  - Release signing.
  - GitHub Release creation.
  - GitHub Pages publishing.
  - Required GitHub Actions secrets.
- [ ] Document release versioning/tag conventions.
- [ ] Document APK naming conventions.
- [x] Document release signing certificate verification.
- [x] Document APK config integrity verification.
  - Extract YAML from final APK.
  - Require non-empty config assets.
  - Compare against repository assets byte-for-byte.
  - Publish YAML as GitHub Release assets.
- [ ] Document the generated `latest.json` schema.

## Release Download Page

- [x] Document the permanent Survey2026 download-page URL.
- [x] Document APK download behavior.
- [x] Document YAML config links.
  - Browser-view URL.
  - Release-asset download URL.
  - Exact-commit/release pinning behavior.
- [ ] Consider displaying release notes/features directly on the download page.
- [ ] Consider exposing config-file SHA-256 hashes independently from the APK hash.

## Repository Maintenance

- [ ] Keep `whisper.cpp` pinned to a known submodule commit.
- [ ] Periodically verify that README architecture descriptions still match the repository.
- [ ] Remove stale documentation when implementation changes.
- [ ] Keep generated/build outputs out of Git.
- [ ] Keep model binaries out of Git unless intentionally distributed.

## Security

- [ ] Verify that release keystores are never committed.
- [ ] Verify that API tokens and credentials are never committed.
- [ ] Keep release signing material exclusively in GitHub Actions secrets or another approved secret store.

## Documentation Policy

- [x] Keep `README.md` focused on current, verified behavior.
- [x] Keep unfinished work and future improvements in this `TODO.md`.
- [ ] When a TODO is completed:
  1. Update the relevant implementation if required.
  2. Update `README.md` with the verified behavior.
  3. Mark or remove the corresponding TODO here.
