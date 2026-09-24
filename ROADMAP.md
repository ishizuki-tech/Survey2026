# Survey2026 Roadmap — Current Baseline and Next Work

## Current Baseline

Stable release baseline:
- Main commit: `92de06de2dec83c78aa41f240d6c224a95d84af1` (`92de06d`)
- Published release: `build-87-92de06d`
- APK: `Survey2026-92de06d-release.apk`
- APK SHA-256: `fc0b16068940cc3e4e93081b4c07c2609ef221402b6afe3cba30d8d41163ac8b`
- Signing certificate SHA-256: `d6edef47ec6734a46122ab7fddb5e4f17d19d4d51ae3eaddfb32f309ee5036ea`
- Pixel 9a manual E2E: PASS
- Download Page / `latest.json`: release #87 metadata confirmed
- Core upload/finalization flow is the stable baseline unless new evidence shows a regression.

Validated merge candidate:
- Branch: `codex/pending-survey-discovery`
- Head: `2ea62b26d46fba0aba88cb670c7b7ebf5ee3ec42`
- Relative to current `main`: ahead 11, behind 0
- Local JVM unit tests: PASS
- Local release assembly: PASS
- Release Gradle signing config: `none` (no forced debug signing)
- Branch CI #44: pending at time of this roadmap update

## Stable Baseline — Complete

### Survey finalization and upload
- Review -> Finish -> queue -> Done
- Mandatory survey JSON staging and WorkManager enqueue
- Offline queueing with reconnect upload eligibility
- Duplicate logical-survey protection by survey UUID
- Pending JSON reuse for repeated finalization attempts
- Reboot/app-update recovery path
- Uploaded / Pending / Device ID status
- Timestamp + device-tagged survey and voice filenames
- One authoritative survey JSON serializer
- Voice/log upload scheduling separated from mandatory survey JSON
- Done screen contains no survey upload actions
- Start New Survey is reset/navigation only

### Pending-survey discovery / recovery
Validated on the current merge candidate:
- Grouped pending-survey discovery with canonical artifact selection
- Shared recovery through `SurveyUploadRescheduler.recoverPendingSurveyUploads(...)`
- Reconciliation through `SurveyUploadWork.reconcile(...)`
- Work tracking through `SurveyUploadWorkTracker`
- Duplicate-file accounting and canonical logical-survey identity
- Startup recovery
- `BOOT_COMPLETED` recovery
- `MY_PACKAGE_REPLACED` recovery
- Network reconnect -> automatic upload
- Post-success startup with `discovered=0` / no resubmit
- Receiver recovery uses the same shared survey recovery path
- Old direct `reenqueuePendingSurveyUploads()` path is not part of the current design

Real-device validation:
- Pixel 9a / Android 16
- Offline pending survey recovery: PASS
- Network restore and remote upload: PASS
- Reboot recovery: PASS
- App-update recovery: PASS
- Duplicate suppression: PASS
- Post-success cleanup / no duplicate submit: PASS

Known validation limitation:
- App-side `LOCKED_BOOT_COMPLETED` handling has not been directly evidenced in logs. This does not block the current recovery merge, but remains a device-validation observation point.

### AI / follow-up deterministic baseline
- TWO_STEP path active for shipped Q8-Q17 English and Swahili configs
- Strict evaluation JSON parsing
- Score / missing_points / followup_needed validation
- Follow-up capacity and duplicate normalization
- Run/survey ownership and cancellation isolation
- Fail-closed behavior for malformed evaluation output

### Native speech baseline
- On-device whisper.cpp JNI integration
- whisper.cpp pinned to v1.9.3
- Bundled baseline model: `models/ggml-small-q5_1.bin`

### Release / CI baseline
- Pushes to `main` run build/lint/JVM/release-APK artifact generation
- Manual `workflow_dispatch` with release publication performs signed release publication and Pages update
- Branch pushes run JVM tests, build a branch preview APK, and publish branch preview metadata
- Release signing key is separate from Android debug signing
- Local release builds are no longer forcibly signed with the Android debug key
- The production release keystore certificate matches the published release signer
- Local release-signed APK update over the published release was verified on Pixel 9a with `versionCode=88`

## P0 — Field Deployment Readiness

### 1. Stable release provenance and distribution

Goal:
Make the field APK identity explicit and independently verifiable.

Completed / verified:
- Source commit SHA is published in release metadata
- Release tag is published
- APK SHA-256 is published
- Signing certificate SHA-256 is published
- `latest.json` was verified against release #87 after the Pages job was re-run
- Pixel 9a install/update path using the production signing certificate was verified

Remaining:
- English config SHA-256
- Swahili config SHA-256
- "What's New" section
- Verified-device / deployment-status section
- Publish-time assertion that the requested stable checkpoint matches the source SHA
- Automated consistency check between rendered Download Page, GitHub Release, and `latest.json`

Done criteria:
- A freshly published signed release is generated from the intended stable main checkpoint.
- GitHub Release and Pages display matching source/tag/hash metadata.
- `latest.json` contains the same release identity and hashes.
- APK/config provenance is traceable without guessing.

### 2. Field verification evidence

Goal:
Turn successful manual verification into a repeatable, recorded acceptance artifact.

Already observed on Pixel 9a:
- Online/remote survey upload success
- Offline pending recovery
- Network reconnect -> automatic upload
- Restart/startup recovery
- Reboot recovery
- App-update / `MY_PACKAGE_REPLACED` recovery
- Duplicate suppression
- Post-success `discovered=0` / no re-submit
- Release-signed local APK installed over the published release

Still to formalize as repository evidence:
- A single acceptance document containing command/log evidence
- Uploaded/Pending count screenshots or log evidence
- One JSON per survey UUID evidence
- Remote artifact/path confirmation
- Explicit device/OS/build identity per acceptance run

Done criteria:
- Results are recorded in repository documentation or release evidence.

### 3. Microphone-denial product behavior

Goal:
Define and implement field-safe behavior when microphone permission is denied.

Decision required:
- Support text-only survey completion when microphone permission is denied, or
- Explicitly make microphone permission mandatory and communicate that before survey start.

Preferred product direction:
- Preserve text-only completion unless a survey configuration explicitly requires voice.

Done criteria:
- Behavior is explicit, tested, and documented.

### 4. Data-handling contract

Goal:
Document what is collected, where it goes, and how it is retained.

Scope:
- Survey JSON
- WAV recordings
- Runtime logs / diagnostics
- Models
- Hashed device tag
- Remote upload destinations
- Retention / deletion expectations
- Operator access

Done criteria:
- Field operators and maintainers can identify storage, upload, retention, and deletion behavior from one documented source.

## P1 — AI Quality / Follow-up Reliability

### Deterministic app logic

Status:
Mostly complete. Avoid speculative runtime redesign.

Preserve:
- strict parsing
- capacity limits
- duplicate normalization
- run ownership
- cancellation isolation
- fail-closed behavior
- retry-safe respondent input

### Prompt/config behavior

Required:
- Review fixtures for Q8-Q17 English and Swahili
- Expected missing-information target per question
- Expected follow-up intent
- Explicit prohibited duplicate/rephrase cases

Done criteria:
- Each shipped two-step prompt pair has an auditable fixture.

### Real-model semantic behavior

Measure:
- Valid evaluation rate
- Unexpected no-follow-up rate
- Unnecessary follow-up rate
- Follow-up relevance
- Duplicate rate
- Timeout/recovery behavior
- English/Swahili parity

Done criteria:
- Controlled target-device evidence exists for the shipped model/config pair.

## P1 — Speech Recognition Quality

Goal:
Replace anecdotal ASR comparison with a reproducible CER/WER process.

Required:
- Immutable reference transcripts
- CER harness
- WER harness
- Speaker matrix
- Device matrix
- Quiet/noisy conditions
- Controlled microphone distance
- Controlled speech rate
- English and Swahili coverage

Current caution:
- Historical Swahili branch experiments are not production evidence for current main.

Done criteria:
- A repeatable benchmark can compare baseline and candidate Whisper models on target devices.

## P1 — Voice / Microphone UX

Document and validate the distinct stages:
1. Microphone capture
2. WAV persistence
3. Whisper transcription
4. Answer text ownership
5. SLM evaluation
6. Follow-up text generation
7. Android TTS playback

Important distinction:
- The SLM generates text.
- Whisper performs speech-to-text.
- Android TTS speaks questions/follow-ups.

Required:
- User-visible recording/transcribing/failure/retry states
- Stable survey/node ownership for transcription results
- Permission-denial behavior aligned with P0

## P2 — Reliability / Soak / Device Compatibility

Scenarios:
- Cancellation
- Rotation
- Background/foreground
- Process restart
- App update
- Network flapping
- Low storage
- Long-session soak

Devices:
- Pixel 9a baseline
- Samsung validation after supported-device / ABI policy is defined

Done criteria:
- Supported device/ABI matrix exists.
- Regressions are reproducible and logged.

## P2 — Release / CI / Download Page

Keep:
- Main push = build/release pipeline
- Signed production APKs use the dedicated release certificate
- Branch preview builds remain separate from production releases

Improve:
- Stable checkpoint assertion
- Config hashes
- "What's New"
- Deployment status
- Verified-device information
- Automated `latest.json` parity with rendered page and GitHub Release

## P3 — Documentation / Toolchain / Maintenance

Required:
- Replace generic README examples with exact current repo paths/settings
- Document exact CMake wiring and ABI configuration
- Document storage layout
- Document SurveyConfig schema/validation
- Document diagnostics/log retention and upload behavior
- Document test tiers and release workflow
- Document local release-signing procedure without committing secrets
- Keep toolchain versions pinned and current
- Keep whisper.cpp pinned
- Keep generated outputs/models/secrets out of Git unless intentionally distributed

## Validation Matrix

| Area | JVM | Instrumentation | Real Device | Release/CI |
| --- | --- | --- | --- | --- |
| Finalization/upload logic | Yes | Partial | Pixel 9a PASS | Branch/main build coverage |
| Pending discovery/reconciliation | Yes | Partial | Pixel 9a PASS | Branch CI |
| Reboot/update recovery | Yes | Partial | Pixel 9a PASS | N/A |
| Duplicate suppression | Yes | Partial | Pixel 9a PASS | N/A |
| Release signing/update path | N/A | N/A | Pixel 9a PASS | Signed release + local signer verification |
| Follow-up deterministic policy | Yes | Scripted coverage exists | Semantic validation pending | N/A |
| Whisper integration | Build coverage | Limited | Benchmark pending | Release assembly |
| Microphone denial | Pending | Pending | Pending | N/A |
| Release provenance | N/A | N/A | Install/update smoke PASS | Partial; config hashes/checks pending |

## Deferred Work / Non-goals

- No redesign of Review -> Finish -> Done without regression evidence.
- No Exit-button upload path; Done screen remains free of survey-upload actions.
- No interviewer-ID system while the product assumption remains one interviewer per device.
- No speculative SLM runtime redesign before semantic evaluation identifies a concrete failure.
- No Swahili production-model switch based only on branch-preview or Mac timing.
