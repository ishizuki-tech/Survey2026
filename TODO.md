# Survey2026 TODO

This file tracks concrete unfinished implementation, verification, and documentation work.
Completed baseline capabilities belong in `ROADMAP.md` and should not remain as perpetual unchecked TODOs.

## P0 — Field Deployment Readiness

### Release provenance / Download Page
- [ ] Assert intended stable checkpoint/source SHA during manual publish.
- [ ] Publish and display the stable tag/checkpoint on the download page.
- [ ] Publish APK SHA-256, English config SHA-256, Swahili config SHA-256, and signing certificate SHA-256.
- [ ] Add a concise "What's New" section to the download page.
- [ ] Add verified-device / deployment-status information.
- [ ] Keep `latest.json` metadata consistent with the rendered download page and GitHub Release.

### Field verification evidence
- [ ] Record Pixel 9a manual E2E evidence for online Finish/upload.
- [ ] Record offline Finish -> pending -> reconnect auto-upload evidence.
- [ ] Record restart/reboot/app-update recovery evidence.
- [ ] Record duplicate-Finish / one-JSON-per-UUID evidence.
- [ ] Record Uploaded/Pending count and remote artifact/path checks.

### Microphone permission behavior
- [ ] Decide and document whether microphone denial permits text-only completion.
- [ ] Implement and test the selected behavior.

### Data handling
- [ ] Document destinations, retention, deletion, recovery identity, and operator access for survey JSON, WAV, logs, models, and hashed device tags.

## P1 — AI Follow-up Quality

- [ ] Create English Q8-Q17 prompt/config review fixtures.
- [ ] Create Swahili Q8-Q17 prompt/config review fixtures.
- [ ] Define expected missing-information targets and prohibited duplicate follow-ups.
- [ ] Build a controlled real-model semantic acceptance matrix.
- [ ] Measure unexpected no-follow-up, unnecessary follow-up, relevance, duplicate rate, timeout/recovery, and language parity.

## P1 — Speech Recognition Quality

- [ ] Build a reproducible CER harness.
- [ ] Build a reproducible WER harness.
- [ ] Create immutable reference transcripts.
- [ ] Define speaker/device/noise/distance/speech-rate test matrix.
- [ ] Run English target-device benchmark.
- [ ] Run Swahili target-device benchmark.
- [ ] Select a production Swahili model only from target-device evidence on current main.

## P1 — Voice / Microphone UX

- [ ] Document microphone capture -> WAV -> Whisper -> answer text -> SLM -> TTS ownership.
- [ ] Verify user-visible recording/transcribing/failure/retry states.
- [ ] Verify transcription results remain owned by the correct survey/node across lifecycle changes.

## P2 — Reliability / Device Compatibility

- [ ] Soak-test cancellation.
- [ ] Soak-test rotation and background/foreground transitions.
- [ ] Soak-test process restart and app update.
- [ ] Soak-test network flapping.
- [ ] Soak-test low-storage behavior.
- [ ] Define supported device/ABI policy.
- [ ] Validate Samsung target only after the supported-device matrix is defined.

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
- [ ] Document build-only versus published-release workflow behavior.

### Repository maintenance
- [ ] Periodically verify README architecture descriptions match the repository.
- [ ] Keep generated outputs and model binaries out of Git unless intentionally distributed.
- [ ] Verify release keystores, API tokens, and other credentials are never committed.

## Completed / Removed From TODO

The following are baseline capabilities and are tracked as complete in `ROADMAP.md`:
- Review -> Finish -> queue -> Done
- Offline upload queueing
- Duplicate logical-survey protection
- Pending JSON reuse
- Reboot/app-update recovery implementation
- Uploaded/Pending/Device status UI
- Timestamp/device-tagged exports
- Single survey JSON serializer
- Voice/log scheduling separation
- Active shipped Q8-Q17 TWO_STEP semantics
- whisper.cpp pinned at v1.9.3
