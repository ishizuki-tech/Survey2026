# Survey2026 TODO

This file tracks concrete unfinished implementation, verification, and documentation work.
Completed baseline capabilities belong in `ROADMAP.md` and should not remain as perpetual unchecked TODOs.

## P0 — Field Deployment Readiness

### Kiambu questionnaire validation before merge
The active Kiambu work is on `feature/kiambu-introduction-consent` and is not merged into `main`.

Remaining:
- [ ] Run a full English Introduction -> Consent -> Q1-Q16 target-device acceptance pass.
- [ ] Run a full Swahili Introduction -> Consent -> Q1-Q16 target-device acceptance pass.
- [ ] Verify Q6 screen-out behavior on the current Kiambu branch.
- [ ] Verify Introduction/Consent TTS behavior on both language paths.
- [ ] Resolve or explicitly accept the remaining source-fidelity differences before merge, including the Swahili consent option parentheticals and the Q1 Other/Nyingine label ordering.
- [ ] Record final Kiambu acceptance evidence before opening/merging the production PR.

Already observed during current branch validation:
- English consent Yes -> Q1 works.
- English consent No -> dedicated STOP / ConsentDeclined flow works.
- The STOP flow does not use the normal Done/export/upload path.

### Release provenance / Download Page
Remaining:
- [ ] Add verified-device / deployment-status information.
- [ ] Add an automated consistency check between the rendered Download Page, GitHub Release, and `latest.json`.

Completed on the current release pipeline:
- [x] Publish source commit SHA and release tag.
- [x] Publish APK SHA-256.
- [x] Publish signing certificate SHA-256.
- [x] Publish English config SHA-256.
- [x] Publish Swahili config SHA-256.
- [x] Publish the exact English and Swahili config files as release assets.
- [x] Generate a dynamic "What's New" section from merged PR/commit metadata.
- [x] Assert the requested stable checkpoint/source SHA during manual release publication.
- [x] Publish QR download metadata/image.
- [x] Standardize main release titles as `MAIN #<run> · <short-sha>`.
- [x] Standardize branch prerelease titles as `PREVIEW #<run> · <branch> · <short-sha>`.
- [x] Retain each successful branch preview as its own prerelease.
- [x] Fix main release publication so `SHORT_SHA` is passed into the release step.
- [x] Current `gh-pages/latest.json` matches release `build-95-c7d668a`.

### Field verification evidence
The core runtime behavior has already been manually observed on Pixel 9a. Remaining work is to turn that validation into durable repository evidence.

- [ ] Create one acceptance document containing the relevant commands/log evidence.
- [ ] Record explicit device / Android / build identity for each acceptance run.
- [ ] Record Uploaded/Pending count evidence.
- [ ] Record one-JSON-per-survey-UUID evidence.
- [ ] Record remote artifact/path confirmation.
- [ ] Record direct app-side `LOCKED_BOOT_COMPLETED` evidence if this path is required for the supported-device contract.

Already observed and tracked in `ROADMAP.md`:
- Online/remote survey upload success.
- Offline pending survey recovery.
- Network reconnect -> automatic upload.
- Restart/startup recovery.
- Reboot recovery.
- App-update / `MY_PACKAGE_REPLACED` recovery.
- Duplicate suppression.
- Post-success `discovered=0` / no re-submit.

### Microphone permission behavior
- [ ] Decide and document whether microphone denial permits text-only completion.
- [ ] Implement and test the selected behavior.

### Data handling
- [ ] Document destinations, retention, deletion, recovery identity, and operator access for survey JSON, WAV, logs, models, and hashed device tags.

## P1 — AI Follow-up Quality

### Prompt/config review
The stable main release still uses the pre-Kiambu Q8-Q17 numbering. The current Kiambu branch renumbers the AI questionnaire section to Q7-Q16.

- [ ] Create English Q7-Q16 prompt/config review fixtures for the Kiambu questionnaire.
- [ ] Create Swahili Q7-Q16 prompt/config review fixtures for the Kiambu questionnaire.
- [ ] Define expected missing-information targets per question.
- [ ] Define prohibited duplicate/rephrase follow-ups per question.
- [ ] Reconfirm component-ID mappings after the questionnaire renumbering.

### Real-model semantic acceptance
- [ ] Build a controlled real-model semantic acceptance matrix.
- [ ] Measure valid evaluation rate.
- [ ] Measure unexpected no-follow-up rate.
- [ ] Measure unnecessary follow-up rate.
- [ ] Measure follow-up relevance.
- [ ] Measure duplicate follow-up rate.
- [ ] Measure timeout/recovery behavior.
- [ ] Measure English/Swahili parity.

Completed deterministic baseline:
- [x] Strict EVAL JSON parsing and validation.
- [x] Low-score normalization when `followup_needed` is omitted but valid unresolved `missing_points` are present.
- [x] Step-2 follow-up admission uses extracted follow-up candidates rather than raw model text.
- [x] Duplicate follow-up normalization.
- [x] Structured component IDs with deterministic ID -> text mapping for livestock, seed-source, and market-destination follow-ups.
- [x] Run/survey ownership and cancellation isolation.

## P1 — Speech Recognition Quality

### Benchmark harness
- [ ] Build a reproducible CER harness.
- [ ] Build a reproducible WER harness.
- [ ] Create immutable reference transcripts.
- [ ] Define speaker/device/noise/distance/speech-rate test matrix.

### Target-device evaluation
- [ ] Run English target-device benchmark.
- [ ] Run Swahili target-device benchmark.
- [ ] Select a production Swahili model only from target-device evidence on the current production baseline.

## P1 — Voice / Microphone UX

- [ ] Document microphone capture -> WAV -> Whisper -> answer text -> SLM -> TTS ownership.
- [ ] Verify user-visible recording state.
- [ ] Verify user-visible transcribing state.
- [ ] Verify failure/retry states.
- [ ] Verify transcription results remain owned by the correct survey/node across lifecycle changes.

## P2 — Reliability / Device Compatibility

### Soak / lifecycle
- [ ] Soak-test cancellation.
- [ ] Soak-test rotation.
- [ ] Soak-test background/foreground transitions.
- [ ] Soak-test process restart.
- [ ] Soak-test app update.
- [ ] Soak-test network flapping.
- [ ] Soak-test low-storage behavior.
- [ ] Run long-session soak testing.

### Supported devices
- [ ] Define supported device/ABI policy.
- [ ] Validate Samsung Galaxy S25 only after the supported-device matrix is defined.

## P2 — Release / CI / Download Page

Remaining:
- [ ] Add deployment-status / verified-device metadata.
- [ ] Add automated parity validation for GitHub Release, rendered Download Page, and `latest.json`.
- [ ] Document the local release-signing workflow without committing secrets.

Completed implementation:
- [x] Stable-checkpoint assertion for manual release publication.
- [x] Config hashes in release/download metadata.
- [x] Dynamic "What's New" in release/download metadata.
- [x] Automatic main release publication and Pages update on `main` pushes.
- [x] Retained branch prereleases with APK + optional QR assets.
- [x] Per-run branch preview tags avoid replacing older tester builds.

## P3 — Documentation / Toolchain / Maintenance

### Native / build
- [ ] Document the actual CMake path and current `WHISPER_DIR` wiring.
- [ ] Document current ABI configuration.
- [ ] Document the pinned NDK/CMake versions and CI/local ownership.

### SLM
- [ ] Document prompt resolver APIs and current ONE_STEP / TWO_STEP behavior.
- [ ] Document model-run isolation, cancellation, terminal-result invariants, and context/token behavior.
- [ ] Document LiteRT-LM model loading, storage, discovery, and integrity behavior.

### Whisper / native audio
- [ ] Document exact whisper.cpp integration.
- [ ] Document audio format, WAV/raw behavior, JNI ownership, and threading.
- [ ] Document Whisper model location, naming, discovery, and integrity behavior.

### Android storage / permissions
- [ ] Document exact manifest/runtime permissions.
- [ ] Document application storage for survey state, recordings, models, logs, and crash diagnostics.

### Diagnostics
- [ ] Document log locations, retention, crash capture, and diagnostic upload behavior.
- [ ] Document diagnostic configuration handling without exposing secrets.

### Survey configuration
- [ ] Document SurveyConfig schema, validation rules, selection flow, INFO/STOP nodes, and new-language/config process.

### Testing / CI
- [ ] Document JVM, instrumentation, real-model, and real-device test tiers.
- [ ] Define policy for which real-model tests, if any, run automatically in CI.
- [ ] Document main-build / GitHub Release / Pages behavior.
- [ ] Document retained branch-preview prerelease behavior.

### Repository maintenance
- [ ] Periodically verify README architecture descriptions match the repository.
- [ ] Keep generated outputs and model binaries out of Git unless intentionally distributed.
- [ ] Verify release keystores, API tokens, and other credentials are never committed.

## Completed / Removed From TODO

### Survey finalization / upload
- Review -> Finish -> queue -> Done.
- Mandatory survey JSON staging and WorkManager enqueue.
- Offline upload queueing.
- Duplicate logical-survey protection.
- Pending JSON reuse.
- Uploaded/Pending/Device status UI.
- Timestamp/device-tagged exports.
- Single survey JSON serializer.
- Voice/log scheduling separation.

### Pending survey discovery / recovery
- Grouped pending-survey discovery.
- Canonical artifact selection.
- Shared recovery via `SurveyUploadRescheduler.recoverPendingSurveyUploads(...)`.
- Reconciliation via `SurveyUploadWork.reconcile(...)`.
- Survey work tracking.
- Startup recovery.
- Reboot recovery.
- App-update / `MY_PACKAGE_REPLACED` recovery.
- Network reconnect auto-upload.
- Duplicate suppression.
- Post-success no-resubmit behavior.
- Receiver recovery unified onto the shared recovery path.
- Old direct `reenqueuePendingSurveyUploads()` path removed from the current design.

### AI / speech baseline
- TWO_STEP evaluation + follow-up flow.
- Strict evaluation JSON handling.
- Structured component-ID follow-up mapping.
- Run/survey ownership and cancellation isolation.
- whisper.cpp pinned at v1.9.3.
- Bundled baseline Whisper model remains `models/ggml-small-q5_1.bin`.

### Current Kiambu branch implementation — pending merge
- Q1-Q16 questionnaire graph migration.
- Config-driven INFO node for questionnaire Introduction.
- Config-driven Consent node.
- Dedicated STOP / ConsentDeclined terminal path.
- Android TTS integration for read-aloud Introduction/Consent.
- English and Swahili navigation/config tests.
- Latest `main` merged into `feature/kiambu-introduction-consent`.

### Release / signing validation
- Current production release: `build-95-c7d668a` / `MAIN #95 · c7d668a`.
- APK/config/signing hashes published.
- Dynamic "What's New" published.
- Download Page and `latest.json` generated for release #95.
- Production signing certificate verified locally.
- Local release-signed APK update over a published release verified on Pixel 9a.
- Forced debug signing removed from the local release Gradle configuration.
- Branch preview releases are retained per successful run.
