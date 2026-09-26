# Survey2026 TODO

This file contains **unfinished work only**.

Planning layers:
- `ROADMAP.md` — baseline, strategic direction, and workstream boundaries
- GitHub Project #2 — live Priority / Area / Status
- `TODO.md` — concrete remaining checklist

Do not copy completed baseline work back into this file. Issues #1-#8 are intentionally outside Project #2 and are not managed here.

## P0 — Field Deployment Readiness

### #36 — Kiambu questionnaire acceptance

- [ ] Run a full English Introduction -> Consent -> Q1-Q16 target-device acceptance pass.
- [ ] Run a full Swahili Introduction -> Consent -> Q1-Q16 target-device acceptance pass.
- [ ] Verify Q6 screen-out behavior on the current Kiambu branch.
- [ ] Verify Introduction/Consent TTS behavior on both language paths.
- [ ] Resolve or explicitly accept the remaining source-fidelity differences before merge, including the Swahili consent option parentheticals and Q1 Other/Nyingine label ordering.
- [ ] Record final Kiambu acceptance evidence before opening/merging the production PR.

### #37 — Stable release provenance and distribution

- [ ] Add verified-device / deployment-status information.
- [ ] Add an automated consistency check between the rendered Download Page, GitHub Release, and `latest.json`.

### #38 — Field verification evidence

- [ ] Create one acceptance document containing the relevant commands/log evidence.
- [ ] Record explicit device / Android / build identity for each acceptance run.
- [ ] Record Uploaded/Pending count evidence.
- [ ] Record one-JSON-per-survey-UUID evidence.
- [ ] Record remote artifact/path confirmation.
- [ ] Record direct app-side `LOCKED_BOOT_COMPLETED` evidence if this path is required for the supported-device contract.

### #39 — Microphone-denial product behavior

- [ ] Decide and document whether microphone denial permits text-only completion or whether microphone access is mandatory.
- [ ] Implement the selected behavior.
- [ ] Test the selected behavior.
- [ ] Document the user-visible behavior.

### #40 — Data-handling contract

- [ ] Document survey JSON storage, upload destination, retention, deletion, recovery identity, and operator access.
- [ ] Document WAV recording storage, upload destination, retention, deletion, and operator access.
- [ ] Document runtime log / diagnostic storage, upload destination, retention, deletion, and operator access.
- [ ] Document model storage/discovery and retention expectations.
- [ ] Document hashed device-tag purpose and handling.

## P1 — Quality

### #41 — AI follow-up quality and semantic acceptance

- [ ] Create English Q7-Q16 prompt/config review fixtures for the Kiambu questionnaire.
- [ ] Create Swahili Q7-Q16 prompt/config review fixtures for the Kiambu questionnaire.
- [ ] Define expected missing-information targets per question.
- [ ] Define expected follow-up intent per question.
- [ ] Define prohibited duplicate/rephrase follow-ups per question.
- [ ] Reconfirm component-ID mappings after questionnaire renumbering.
- [ ] Build a controlled real-model semantic acceptance matrix.
- [ ] Measure valid evaluation rate.
- [ ] Measure unexpected no-follow-up rate.
- [ ] Measure unnecessary follow-up rate.
- [ ] Measure follow-up relevance.
- [ ] Measure duplicate follow-up rate.
- [ ] Measure timeout/recovery behavior.
- [ ] Measure English/Swahili parity.

### #42 — Speech recognition quality benchmark

- [ ] Build a reproducible CER harness.
- [ ] Build a reproducible WER harness.
- [ ] Create immutable reference transcripts.
- [ ] Define speaker/device/noise/distance/speech-rate test matrix.
- [ ] Run English target-device benchmark.
- [ ] Run Swahili target-device benchmark.
- [ ] Select a production Swahili model only from target-device evidence on the current production baseline.

### #43 — Voice and microphone UX validation

- [ ] Document microphone capture -> WAV -> Whisper -> answer text -> SLM -> TTS ownership.
- [ ] Verify user-visible recording state.
- [ ] Verify user-visible transcribing state.
- [ ] Verify failure/retry states.
- [ ] Verify transcription results remain owned by the correct survey/node across lifecycle changes.
- [ ] Align permission-denial behavior with #39.
- [ ] Validate Kiambu Introduction/Consent TTS on target device.

## P2 — Reliability / Compatibility / CI

### #44 — Reliability and lifecycle soak testing

- [ ] Soak-test cancellation.
- [ ] Soak-test rotation.
- [ ] Soak-test background/foreground transitions.
- [ ] Soak-test process restart.
- [ ] Soak-test app update.
- [ ] Soak-test network flapping.
- [ ] Soak-test low-storage behavior.
- [ ] Run long-session soak testing.

### #45 — Supported-device and ABI compatibility

- [ ] Define supported-device policy.
- [ ] Define supported-ABI policy.
- [ ] Record the supported device/ABI matrix.
- [ ] Validate Samsung Galaxy S25 after the matrix is defined.

### #46 — Release, CI, and Download Page hardening

- [ ] Document the local release-signing workflow without committing secrets.
- [ ] Document main-build / GitHub Release / Pages behavior.
- [ ] Document retained branch-preview prerelease behavior.

Release/Page/`latest.json` parity and verified-device metadata are owned by P0 issue #37.

## P3 — Documentation / Toolchain / Maintenance

### #47 — Documentation, toolchain, and repository maintenance

- [ ] Refresh README baseline from the old release #87 / `92de06d` references to the current `main c7d668a` / release #95 baseline.
- [ ] Refresh README release/CI text to reflect config hashes, dynamic "What's New", automatic main publication, and retained branch prereleases.
- [ ] Document the actual CMake path and current `WHISPER_DIR` wiring.
- [ ] Document current ABI configuration.
- [ ] Document pinned NDK/CMake versions and CI/local ownership.
- [ ] Document prompt resolver APIs and current ONE_STEP / TWO_STEP behavior.
- [ ] Document model-run isolation, cancellation, terminal-result invariants, and context/token behavior.
- [ ] Document LiteRT-LM model loading, storage, discovery, and integrity behavior.
- [ ] Document exact whisper.cpp integration.
- [ ] Document audio format, WAV/raw behavior, JNI ownership, and threading.
- [ ] Document Whisper model location, naming, discovery, and integrity behavior.
- [ ] Document exact manifest/runtime permissions.
- [ ] Document application storage for survey state, recordings, models, logs, and crash diagnostics.
- [ ] Document log locations, retention, crash capture, and diagnostic upload behavior.
- [ ] Document diagnostic configuration handling without exposing secrets.
- [ ] Document SurveyConfig schema, validation rules, selection flow, INFO/STOP nodes, and new-language/config process.
- [ ] Document JVM, instrumentation, real-model, and real-device test tiers.
- [ ] Define policy for which real-model tests, if any, run automatically in CI.
- [ ] Periodically verify README architecture descriptions remain aligned with the repository after the current refresh.
- [ ] Keep generated outputs and model binaries out of Git unless intentionally distributed.
- [ ] Verify release keystores, API tokens, and other credentials are never committed.
