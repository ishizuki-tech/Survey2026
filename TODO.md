# Survey2026 TODO — Unfinished Work

This document contains **concrete unfinished work only**. It is a documentation snapshot, **not** a second source of live Issue Status, Priority, or Area. For current ownership and execution fields use the Survey2026 Roadmap GitHub Project #2 when accessible; for active definitions and acceptance criteria use [GitHub Issues](https://github.com/ishizuki-tech/Survey2026/issues?q=is%3Aissue+is%3Aopen). Verify both before selecting work.

`ROADMAP.md` records stable architecture, validated historical milestones, and direction. Do not retain completed Issues as active checklists. Historical Issues #1–#8 are outside the Roadmap Project scope.

## Survey correctness and lifecycle

### [#119 — Remove unreachable Q14b survey data](https://github.com/ishizuki-tech/Survey2026/issues/119)

- [ ] On the current `main`, confirm and test the Q14a **Yes → No** route change and every persisted Q14b data owner (answers, AI state, follow-ups and audio references).
- [ ] Remove only now-unreachable Q14b survey data on the changed route, preserving data on the still-valid path and preventing stale export entries.
- [ ] Cover back navigation, repeated changes, unfinished survey save and final JSON export with focused regression tests.

### [#118 — Release Whisper runtime on session exit](https://github.com/ishizuki-tech/Survey2026/issues/118)

- [ ] Verify heavy-runtime ownership across explicit **Stop → Selector** and **Done → Restart → Selector** versus other exits.
- [ ] Apply only an evidenced lifecycle fix that releases the session-owned native runtime without interfering with active/new survey work.
- [ ] Validate cancellation, re-entry, model switching and relevant real-device resource behavior.

### [#124 — Typed completion after microphone denial](https://github.com/ishizuki-tech/Survey2026/issues/124)

- [ ] Implement the approved [#39](https://github.com/ishizuki-tech/Survey2026/issues/39) policy: deny `RECORD_AUDIO` without blocking typed answers, review, finalization or upload.
- [ ] Provide safe permission retry/settings access and restoration after regrant without losing survey data.
- [ ] Add automated and target-device permission denial/regrant coverage. Keep this independent from [#121](https://github.com/ishizuki-tech/Survey2026/issues/121) TTS behavior.

### [#121 — Continue Without Voice session behavior](https://github.com/ishizuki-tech/Survey2026/issues/121)

- [ ] Make **Continue Without Voice** reliably disable TTS playback for the current session, without suppressing typed input or changing recording permission semantics.
- [ ] Verify new-session reset and navigation/re-entry behavior with targeted tests.

## Model acquisition and language quality

### [#120 — Validate manually selected LiteRT-LM models](https://github.com/ishizuki-tech/Survey2026/issues/120)

- [ ] Validate candidate identity/format and integrity **before** replacing a usable private model, persisting selection or reporting success.
- [ ] Preserve the known-good model and retry path on failed validation; test invalid/partial inputs.

### [#123 — Prevent concurrent manual model-import races](https://github.com/ishizuki-tech/Survey2026/issues/123)

- [ ] Verify the shared temporary path / destination race under overlapping picker imports.
- [ ] Serialize or isolate file writes and cancellation cleanup without overwriting a newer valid selection.
- [ ] Add deterministic overlapping-import and failure tests.

### [#98 — Q14/Q15 component-ID omissions](https://github.com/ishizuki-tech/Survey2026/issues/98)

- [ ] Reproduce the missing required-component identification failures on the current model/config/device baseline.
- [ ] Analyze trace outputs and propose the smallest evidence-backed correction while preserving the component catalog.

### [#99 — Q12/Q13 semantic mismatches](https://github.com/ishizuki-tech/Survey2026/issues/99)

- [ ] Reproduce incomplete-answer / follow-up decision mismatches with controlled fixtures.
- [ ] Distinguish prompt/model semantic errors from deterministic parsing/guard errors before changing behavior.

### [#100 — Swahili FOLLOWUP quality](https://github.com/ishizuki-tech/Survey2026/issues/100)

- [ ] Complete human-reviewed Swahili fluency, semantic accuracy and respondent-usability acceptance using repeatable real-model samples.

### [#101 — English follow-up quality and parity](https://github.com/ishizuki-tech/Survey2026/issues/101)

- [ ] Run English Q7–Q16 semantic acceptance fixtures on the approved hardware/model baseline.
- [ ] Compare English/Swahili outcomes without treating structurally valid EVAL/FOLLOWUP responses as semantic acceptance.

## Acceptance, compatibility, and documentation

### [#43 — Voice and microphone UX validation](https://github.com/ishizuki-tech/Survey2026/issues/43)

- [ ] Document the exact capture → WAV → Whisper → answer text → SLM → TTS lifecycle/ownership flow, including introduction/consent playback.
- [ ] Record end-to-end target-device evidence for the current capture/transcribe/recording and follow-up UI states, including exact audio/question/transcript ownership.
- [ ] Verify recording/transcribing/failure/retry presentation, ownership and permissions without duplicating the focused fixes above.

### [#44 — Reliability and lifecycle soak testing](https://github.com/ishizuki-tech/Survey2026/issues/44)

- [ ] Execute a documented, repeatable acceptance matrix for rotation, cancellation, long sessions, app/process restart, background/foreground, network flapping, update and low storage.
- [ ] Record exact APK/source/model/device provenance for failures and create focused follow-up Issues when reproducible.

### [#45 — Supported-device and ABI compatibility](https://github.com/ishizuki-tech/Survey2026/issues/45)

- [ ] Define the supported Android API, device, ABI and runtime-capability policy separately from current `arm64-v8a` build configuration.
- [ ] Collect representative target-device evidence before making a production support claim.

### [#47 — Documentation, toolchain and repository maintenance](https://github.com/ishizuki-tech/Survey2026/issues/47)

- [ ] Audit current model storage/discovery/integrity and model-file ownership, and distinguish development binaries from intentionally shipped assets.
- [ ] Audit exact whisper.cpp integration, `WHISPER_DIR`/JNI/CMake entry points, WAV/raw audio format, native-thread ownership and cleanup.
- [ ] Audit ONE_STEP/TWO_STEP prompt resolver, schema/config validation, INFO/STOP routing and new-language configuration process.
- [ ] Audit app-private survey, WAV, model, log/crash/diagnostic storage, retrieval, retention/deletion and operator access; distinguish policy approvals from actual code behavior.
- [ ] Document unit/instrumentation/real-model/field-device test tiers and decide which real-model tests may run in CI.
- [ ] Validate README links and build instructions and close any remaining confirmed coverage gaps with focused documentation changes.
- [ ] Check NDK/CMake/ABI and CI ownership, release signing, generated-output exclusions and secret-handling documentation for regressions without disclosing credentials.
- [ ] Distinguish completed documentation refresh from outstanding audits; keep #47 open until its acceptance criteria are met.

## Planning maintenance rules

- The issue links above were **open** in the GitHub check against `main` `31fd8c9a9deffedccadc8798ced84401610e178e` on November 9, 2026 (US Pacific time). This is an evidence snapshot, not a claim that these will remain open.
- Do **not** copy Issue label priority into this file as live Project Priority; Project #2 was not readable in this review.
- Completed #37, #39, #40, #41, #42, #51, #69, #86, #128, #138, #139, #155 and the recently closed navigation/UI Issues do not belong in this unfinished checklist.
- If Issue and Project fields conflict, report the difference and use the Project as authority for live Priority/Status/Area.
