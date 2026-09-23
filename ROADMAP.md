# Survey2026 Roadmap — Main Branch Baseline

Baseline:
- Main commit: `2762c91b3339ddfd5a9aa21e784b031b805a635b`
- Stable tag: `upload-flow-stable-2026-09-22`
- Manual Pixel 9a E2E: PASS
- Core upload/finalization flow is frozen as the stable baseline unless new evidence shows a regression.

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
- Manual `workflow_dispatch` with `publish_release=true` performs signed release publication and Pages update

## P0 — Field Deployment Readiness

### 1. Stable release provenance and distribution
Goal:
Make the field APK identity explicit and independently verifiable.

Required:
- Stable checkpoint/tag shown on the download page
- Source commit SHA shown on the download page and release
- APK SHA-256
- English config SHA-256
- Swahili config SHA-256
- Signing certificate SHA-256
- "What's New" summary
- Verified-device note
- Publish-time consistency check between intended stable checkpoint and source SHA

Done criteria:
- A freshly published signed release is generated from the intended stable main checkpoint.
- GitHub Release and Pages display matching source/tag/hash metadata.
- `latest.json` contains the same release identity and hashes.
- APK/config provenance is traceable without guessing.

### 2. Field verification evidence
Goal:
Turn manual verification into a repeatable, recorded acceptance artifact.

Current state:
- Pixel 9a manual E2E passed for the completed upload/finalization flow.

Required evidence:
- Online Finish -> upload
- Offline Finish -> pending
- Network reconnect -> automatic upload
- Restart/recovery
- Reboot/app-update recovery
- Duplicate Finish protection
- Uploaded/Pending count behavior
- One JSON per survey UUID
- Remote path/artifact confirmation

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
- Main push = build-only
- Manual publish = signed release + GitHub Release + Pages

Improve:
- Stable checkpoint assertion
- Source/tag/hash provenance
- Config hashes
- "What's New"
- Deployment status
- Verified-device information
- `latest.json` parity with rendered page

## P3 — Documentation / Toolchain / Maintenance

Required:
- Replace generic README examples with exact current repo paths/settings
- Document exact CMake wiring and ABI configuration
- Document storage layout
- Document SurveyConfig schema/validation
- Document diagnostics/log retention and upload behavior
- Document test tiers and release workflow
- Keep toolchain versions pinned and current
- Keep whisper.cpp pinned
- Keep generated outputs/models/secrets out of Git unless intentionally distributed

## Validation Matrix

| Area | JVM | Instrumentation | Real Device | Release/CI |
| --- | --- | --- | --- | --- |
| Finalization/upload logic | Yes | Partial | Pixel 9a manual PASS | Build pipeline |
| Reboot/update recovery | Yes | Partial | Manual evidence to document | N/A |
| Follow-up deterministic policy | Yes | Scripted coverage exists | Semantic validation pending | N/A |
| Whisper integration | Build coverage | Limited | Benchmark pending | Release assembly |
| Microphone denial | Pending | Pending | Pending | N/A |
| Release provenance | N/A | N/A | Install smoke pending | P0 pending |

## Deferred Work / Non-goals

- No redesign of Review -> Finish -> Done without regression evidence.
- No interviewer-ID system while the product assumption remains one interviewer per device.
- No speculative SLM runtime redesign before semantic evaluation identifies a concrete failure.
- No Swahili production-model switch based only on branch-preview or Mac timing.
