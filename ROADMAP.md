# Survey2026 Roadmap — Current Baseline and Next Work

## Current Baseline

### Stable main release
- Main commit: `c7d668ad26d929c819b340f4722217cef60f7aec` (`c7d668a`)
- Published release: `build-95-c7d668a`
- Release title: `MAIN #95 · c7d668a`
- APK: `Survey2026-c7d668a-release.apk`
- APK SHA-256: `e3d1bc16b1e2339078fc155867d25b8ae677a44f24a7d068f43a4351d906aae7`
- English config SHA-256: `03c61877264de51524e8f12107f0eaee3ed1abe16c7874fae0a77c863fa66d26`
- Swahili config SHA-256: `90f00f7ceadbd2bb0d780f65fd12f23c2610b9d94ea502e51462c860e617f017`
- Signing certificate SHA-256: `d6edef47ec6734a46122ab7fddb5e4f17d19d4d51ae3eaddfb32f309ee5036ea`
- Download Page / `latest.json`: aligned to release #95 / run #95
- Dynamic "What's New": published from merged PR/commit metadata
- Pixel 9a / Android 16 remains the manually validated device baseline for the core survey/upload flow
- Core upload/finalization/recovery behavior remains the production baseline unless new regression evidence appears

### Active Kiambu validation branch — not merged
- Branch: `feature/kiambu-introduction-consent`
- Head: `1240cec157cfdf945122273bc3cc8af22db035ec` (`1240cec`)
- Relative to current `main`: ahead 8, behind 0
- Latest `main` is merged into the branch
- Branch preview releases for `1240cec` were published successfully, including `PREVIEW #71 · feature/kiambu-introduction-consent · 1240cec`
- Questionnaire migrated to Kiambu Q1-Q16 in both English and Swahili configs
- Questionnaire Introduction is a config-driven INFO node with existing Android TTS read-aloud behavior
- Consent is config-driven; decline routes to a dedicated STOP / ConsentDeclined terminal screen
- Navigation/config unit coverage includes INFO, Consent, STOP, and language-specific routes
- Manual English consent validation has observed Yes -> Q1 and No -> STOP
- Swahili physical-device path and full Q1-Q16 acceptance remain pending
- This branch must remain separate from `main` until Kiambu acceptance is complete

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
This work is now part of `main`; it is no longer only a merge candidate.

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
- App-side `LOCKED_BOOT_COMPLETED` handling has not been directly evidenced in logs. This remains a device-validation observation point if that path is part of the supported-device contract.

### AI / follow-up deterministic baseline
- TWO_STEP path active for the shipped main questionnaire
- Main release #95 uses the pre-Kiambu Q8-Q17 numbering
- Current Kiambu branch preserves the same AI flow after renumbering the questionnaire section to Q7-Q16
- Strict evaluation JSON parsing
- Score / `missing_points` / `followup_needed` validation
- Low-score normalization when `followup_needed` is absent but valid unresolved points are present
- Step-2 admission uses extracted follow-up candidates rather than raw generation text
- Follow-up capacity and duplicate normalization
- Structured component IDs with deterministic ID -> text mapping
- Run/survey ownership and cancellation isolation
- Fail-closed behavior for malformed or contradictory evaluation output

### Native speech baseline
- On-device whisper.cpp JNI integration
- whisper.cpp pinned to v1.9.3
- Bundled baseline model: `models/ggml-small-q5_1.bin`

### Release / CI baseline
- Pushes to `main` build the signed release APK and publish the production GitHub Release / Download Page metadata
- Main release titles use `MAIN #<run> · <short-sha>`
- Release metadata publishes source SHA, APK SHA-256, signing certificate SHA-256, English/Swahili config files and hashes, and dynamic "What's New"
- Manual release publication validates the requested stable tag against the source commit
- `gh-pages/latest.json` is generated from the same release pipeline and currently points to release #95
- Branch pushes run the branch preview pipeline
- Every successful branch preview is retained as its own GitHub prerelease with a unique branch/SHA/run tag
- Branch preview titles use `PREVIEW #<run> · <branch> · <short-sha>`
- Branch prereleases include the APK and an optional QR PNG; QR generation failure does not block APK publication
- Release signing key is separate from Android debug signing
- Local release builds are not forcibly signed with the Android debug key
- The production release keystore certificate matches the published release signer

## P0 — Field Deployment Readiness

### 1. Kiambu questionnaire acceptance

Goal:
Validate the current Kiambu questionnaire branch before any merge to `main`.

Implemented on the branch:
- Q1-Q16 English and Swahili questionnaire graph
- INFO Introduction node
- Consent node
- STOP / ConsentDeclined path
- Android TTS read-aloud integration for configured Introduction/Consent nodes
- Q6 screen-out routing
- Existing TWO_STEP AI flow remapped to the new questionnaire numbering

Remaining:
- Full English target-device acceptance through Q1-Q16
- Full Swahili target-device acceptance through Q1-Q16
- Q6 screen-out target-device confirmation
- Introduction/Consent TTS confirmation on both languages
- Final source-fidelity review for the remaining known wording/option-order differences
- Repository evidence for the final acceptance run

Done criteria:
- Both language paths pass the intended target-device flow.
- Consent acceptance and rejection paths are verified.
- Screen-out behavior is verified.
- Any intentional source-text deviations are explicitly documented.
- The branch is ready for a separately reviewed PR into `main`.

### 2. Stable release provenance and distribution

Goal:
Make the field APK identity explicit and independently verifiable.

Completed / verified:
- Source commit SHA and release tag are published
- APK SHA-256 is published
- Signing certificate SHA-256 is published
- English config SHA-256 is published
- Swahili config SHA-256 is published
- Exact config files are release assets
- Dynamic "What's New" is published
- Manual publish has a stable-checkpoint/source-SHA assertion
- Download Page and `latest.json` currently identify release #95 consistently
- Main and preview release titles include run/branch/SHA identity
- Branch previews are retained instead of overwriting the previous tester build

Remaining:
- Verified-device / deployment-status metadata
- Automated parity validation across rendered Download Page, GitHub Release, and `latest.json`

Done criteria:
- GitHub Release, rendered Download Page, and `latest.json` can be automatically checked for the same release identity and hashes.
- Field operators can see which device/build combinations have been verified.

### 3. Field verification evidence

Goal:
Turn successful manual verification into repeatable, recorded acceptance artifacts.

Already observed on Pixel 9a:
- Online/remote survey upload success
- Offline pending recovery
- Network reconnect -> automatic upload
- Restart/startup recovery
- Reboot recovery
- App-update / `MY_PACKAGE_REPLACED` recovery
- Duplicate suppression
- Post-success `discovered=0` / no re-submit
- Release-signed local APK update path

Still to formalize:
- A single acceptance document containing command/log evidence
- Uploaded/Pending count evidence
- One JSON per survey UUID evidence
- Remote artifact/path confirmation
- Explicit device/OS/build identity per acceptance run

Done criteria:
- Results are recorded in repository documentation or release evidence.

### 4. Microphone-denial product behavior

Goal:
Define and implement field-safe behavior when microphone permission is denied.

Decision required:
- Support text-only survey completion when microphone permission is denied, or
- Explicitly make microphone permission mandatory and communicate that before survey start.

Preferred product direction:
- Preserve text-only completion unless a survey configuration explicitly requires voice.

Done criteria:
- Behavior is explicit, tested, and documented.

### 5. Data-handling contract

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
The deterministic app-side baseline is substantially complete. Avoid speculative runtime redesign without failure evidence.

Preserve:
- strict parsing
- capacity limits
- duplicate normalization
- structured component IDs
- deterministic component ID -> text mapping
- run ownership
- cancellation isolation
- fail-closed behavior
- retry-safe respondent input

### Prompt/config behavior

Required for the Kiambu questionnaire:
- Review fixtures for Q7-Q16 English and Swahili
- Expected missing-information target per question
- Expected follow-up intent
- Explicit prohibited duplicate/rephrase cases
- Component-ID mapping review after questionnaire renumbering

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
- Historical Swahili model branches and Mac timing results are not production evidence for the current Android target.

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
- Android TTS speaks configured questions/follow-ups/Introduction/Consent where read-aloud is enabled.

Required:
- User-visible recording/transcribing/failure/retry states
- Stable survey/node ownership for transcription results
- Permission-denial behavior aligned with P0
- Kiambu Introduction/Consent TTS target-device validation

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
- Samsung Galaxy S25 validation after supported-device / ABI policy is defined

Done criteria:
- Supported device/ABI matrix exists.
- Regressions are reproducible and logged.

## P2 — Release / CI / Download Page

Keep:
- Main push = signed production build/release pipeline
- Signed production APKs use the dedicated release certificate
- Branch preview builds remain separate from production releases
- Retained per-run branch prereleases remain available for tester rollback/comparison

Implemented:
- Stable checkpoint assertion for manual publication
- Config files and hashes
- Dynamic "What's New"
- Main/preview release title normalization
- Per-run retained preview releases
- Preview QR assets
- Main release `SHORT_SHA` environment fix

Improve:
- Deployment status
- Verified-device information
- Automated `latest.json` / rendered page / GitHub Release parity validation
- Local release-signing workflow documentation

## P3 — Documentation / Toolchain / Maintenance

Required:
- Keep README examples aligned with exact current repo paths/settings
- Document exact CMake wiring and ABI configuration
- Document storage layout
- Document SurveyConfig schema/validation, including INFO and STOP node types
- Document diagnostics/log retention and upload behavior
- Document test tiers and release workflow
- Document local release-signing procedure without committing secrets
- Keep toolchain versions pinned and current
- Keep whisper.cpp pinned
- Keep generated outputs/models/secrets out of Git unless intentionally distributed

## Validation Matrix

| Area | JVM | Instrumentation | Real Device | Release/CI |
| --- | --- | --- | --- | --- |
| Finalization/upload logic | Yes | Partial | Pixel 9a PASS | Main/branch build coverage |
| Pending discovery/reconciliation | Yes | Partial | Pixel 9a PASS | In main |
| Reboot/update recovery | Yes | Partial | Pixel 9a PASS | N/A |
| Duplicate suppression | Yes | Partial | Pixel 9a PASS | N/A |
| Release signing/update path | N/A | N/A | Pixel 9a PASS | Signed release pipeline |
| Release provenance | N/A | N/A | Install/update baseline PASS | Config hashes + What's New complete; automated parity check pending |
| Follow-up deterministic policy | Yes | Scripted coverage exists | Semantic validation pending | N/A |
| Kiambu INFO/Consent/STOP navigation | Yes | Build coverage | English consent paths observed; full EN/SW pass pending | PREVIEW #71 published |
| Whisper integration | Build coverage | Limited | Benchmark pending | Release assembly |
| Microphone denial | Pending | Pending | Pending | N/A |

## Deferred Work / Non-goals

- Do not merge the current Kiambu branch into `main` until its acceptance work is complete.
- No redesign of Review -> Finish -> Done without regression evidence.
- No Exit-button upload path; Done screen remains free of survey-upload actions.
- No interviewer-ID system while the product assumption remains one interviewer per device.
- No speculative SLM runtime redesign before semantic evaluation identifies a concrete failure.
- No Swahili production-model switch based only on branch-preview or Mac timing.
