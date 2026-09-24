# Survey2026 TODO

This file tracks concrete unfinished implementation, verification, and documentation work.
Completed baseline capabilities belong in `ROADMAP.md` and should not remain as perpetual unchecked TODOs.

## P0 — Field Deployment Readiness

### Release provenance / Download Page
Remaining:
- [ ] Publish English config SHA-256.
- [ ] Publish Swahili config SHA-256.
- [ ] Add a concise "What's New" section to the download page.
- [ ] Add verified-device / deployment-status information.
- [ ] Assert the intended stable checkpoint/source SHA during manual publish.
- [ ] Add an automated consistency check between the rendered Download Page, GitHub Release, and `latest.json`.

Already verified and tracked in `ROADMAP.md`:
- Release tag/source identity for release #87.
- APK SHA-256 publication.
- Signing certificate SHA-256 publication.
- `latest.json` alignment with release #87 after Pages republish.
- Pixel 9a install/update using the production signing certificate.
- Local release build is no longer forcibly signed with the Android debug key.

### Field verification evidence
The runtime behavior has already been manually observed on Pixel 9a. Remaining work is to turn that validation into durable repository evidence.

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
- [ ] Create English Q8-Q17 prompt/config review fixtures.
- [ ] Create Swahili Q8-Q17 prompt/config review fixtures.
- [ ] Define expected missing-information targets per question.
- [ ] Define prohibited duplicate/rephrase follow-ups per question.

### Real-model semantic acceptance
- [ ] Build a controlled real-model semantic acceptance matrix.
- [ ] Measure valid evaluation rate.
- [ ] Measure unexpected no-follow-up rate.
- [ ] Measure unnecessary follow-up rate.
- [ ] Measure follow-up relevance.
- [ ] Measure duplicate follow-up rate.
- [ ] Measure timeout/recovery behavior.
- [ ] Measure English/Swahili parity.

## P1 — Speech Recognition Quality

### Benchmark harness
- [ ] Build a reproducible CER harness.
- [ ] Build a reproducible WER harness.
- [ ] Create immutable reference transcripts.
- [ ] Define speaker/device/noise/distance/speech-rate test matrix.

### Target-device evaluation
- [ ] Run English target-device benchmark.
- [ ] Run Swahili target-device benchmark.
- [ ] Select a production Swahili model only from target-device evidence on current main.

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
- [ ] Validate Samsung target only after the supported-device matrix is defined.

## P2 — Release / CI / Download Page

- [ ] Add stable-checkpoint assertion to release publication.
- [ ] Publish config hashes in release/download metadata.
- [ ] Add "What's New" to release/download metadata.
- [ ] Add deployment-status / verified-device metadata.
- [ ] Add automated parity validation for GitHub Release, rendered Download Page, and `latest.json`.
- [ ] Document the local release-signing workflow without committing secrets.

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
- [ ] Document SurveyConfig schema, validation rules, selection flow, and new-language/config process.

### Testing / CI
- [ ] Document JVM, instrumentation, real-model, and real-device test tiers.
- [ ] Define policy for which real-model tests, if any, run automatically in CI.
- [ ] Document main-build versus published-release workflow behavior.
- [ ] Document branch-preview workflow behavior.

### Repository maintenance
- [ ] Periodically verify README architecture descriptions match the repository.
- [ ] Keep generated outputs and model binaries out of Git unless intentionally distributed.
- [ ] Verify release keystores, API tokens, and other credentials are never committed.

## Completed / Removed From TODO

The following are baseline capabilities or completed validation milestones and are tracked in `ROADMAP.md`:

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
- Active shipped Q8-Q17 TWO_STEP semantics.
- Strict evaluation JSON handling.
- Run/survey ownership and cancellation isolation.
- whisper.cpp pinned at v1.9.3.
- Bundled baseline Whisper model remains `models/ggml-small-q5_1.bin`.

### Release / signing validation
- Published release #87 provenance verified.
- APK SHA-256 published.
- Signing certificate SHA-256 published.
- `latest.json` republished and verified for release #87.
- Production signing certificate verified locally.
- Local release-signed APK update over the published release verified on Pixel 9a.
- Forced debug signing removed from the local release Gradle configuration.
