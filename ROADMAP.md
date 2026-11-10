# Survey2026 Roadmap — Current Baseline and Next Work

## How to use this roadmap

Survey2026 planning is intentionally split into three layers:

1. **`ROADMAP.md`** — product/technical baseline, strategic direction, and workstream boundaries.
2. **GitHub Project #2 — Survey2026 Roadmap** — live execution status, priority, and area for the active workstreams.
3. **`TODO.md`** — concrete unfinished checklist items only.

Completed implementation belongs here in `ROADMAP.md`, not as permanent checked items in `TODO.md`.
Live status should be read from Project #2 rather than duplicated in this file.

Issues #1-#8 are historical/external LiteRT-LM or earlier app-hardening records and are intentionally **not** part of Project #2.

---

## Current Baseline

### Stable main / release identity

- Live production release identity is authoritative in the latest GitHub Release and `gh-pages/latest.json`; this file intentionally does not duplicate the moving release run number or source SHA.
- The production release pipeline publishes source SHA, release tag, APK SHA-256, signing-certificate SHA-256, exact English/Swahili config assets and hashes, and dynamic "What's New".
- Samsung SM-S731U / Android 16 API 36 is a release-device acceptance baseline
  for the integrated Kiambu questionnaire and recovery flow.
- Current V2-only UUIDv7 survey/voice/diagnostic routing and per-recording preservation are the evolved baseline implemented through closed #155 and #138. Historic V1 evidence below is retained explicitly as historical data and is not a new V2 field-acceptance claim.
- Current open issues and live Project fields are not represented by the historical acceptance notes below.

### Kiambu questionnaire baseline — integrated and accepted

- Kiambu was integrated into `main` through PR #53; Issue #36 is complete.
- Build #109 release-device acceptance on Samsung SM-S731U / Android 16 API 36
  verified English and Swahili Q1-Q16 flows, Consent Yes/No, Q6 routing,
  Introduction/Consent TTS, configured Other behavior, and Review -> Finish ->
  Done.
- Questionnaire Introduction remains a config-driven INFO node; Consent is
  config-driven and decline routes to the dedicated ConsentDeclined STOP
  screen.

---

## Stable Baseline — Complete

### Survey finalization and upload

- Historical completed-survey route: Review -> Finish -> queue -> Done; later incomplete-session Exit behavior is tracked separately in closed #159 and must not be conflated with this historical flow.
- Mandatory survey JSON staging and WorkManager enqueue
- Offline queueing with reconnect upload eligibility
- Duplicate logical-survey protection by survey UUID
- Pending JSON reuse for repeated finalization attempts
- Reboot/app-update recovery path
- Uploaded / Pending / Device ID status
- Historical timestamp/device-tagged filename baseline, superseded for new V2 installations by UUIDv7 survey-start UTC path conventions (see V2 baseline below)
- One authoritative survey JSON serializer
- Voice/log upload scheduling separated from mandatory survey JSON
- Done screen contains no survey upload actions
- Historical Done-screen **Start New Survey** reset/navigation behavior; the later survey-start/incomplete-exit changes are tracked in closed #159

### Pending-survey discovery / recovery

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

**Historical field-evidence baseline (pre-V2 protocol)** (Issue #38 complete; see
`docs/field-verification-evidence.md`):
- Offline pending preservation, startup/process-restart recovery, reconnect
  upload, reboot / `BOOT_COMPLETED` recovery, same-UUID canonical JSON upload,
  duplicate/no-resubmit behavior, and post-success `discovered=0`: PASS with
  durable evidence.
- The remote snapshot retains 19 unique final JSON files with matching logcats.
- Optional voice retention is a separate known finding: 42 of 49 referenced
  WAV files were retained and 7 are missing. It is not a failure of mandatory
  survey JSON acceptance.
- Genuine release-signed update recovery is also PASS: Build #109 / versionCode
  109 was updated in place to Build #110 / versionCode 110 with `adb install
  -r`. Pending survey `2e0dfdba-fc52-4719-933a-11eeea283557` survived,
  `MY_PACKAGE_REPLACED` reconciled it with `discovered=1`, `reconciled=1`,
  `duplicates=0`, and `operationalFailures=0`; after connectivity returned,
  the same UUID uploaded to
  `2026-10-02/exports/2026-10-01_19-15-48_survey_SM-S731U_414A86B07D91_2e0dfdba-fc52-4719-933a-11eeea283557.json`.
  **This is an archived V1 destination and naming example, not the V2 upload path.**
  The worker returned SUCCESS and two later recovery runs each reported zero
  discovered, reconciled, duplicate, and operational-failure counts.
- A diagnostic logcat worker separately recorded a FileNotFoundException,
  retry, and failure after another worker had already uploaded that diagnostic
  artifact. This is separate from mandatory survey JSON release-update
  acceptance; no root cause is asserted here.

Known validation limitation:
- App-side `LOCKED_BOOT_COMPLETED` handling has not been directly evidenced in logs. This remains an observation point if that path becomes part of the supported-device contract.

### AI / follow-up deterministic baseline

- TWO_STEP path active for the shipped Kiambu questionnaire Q7–Q16 where configured
- Earlier pre-Kiambu Q8–Q17 numbering is historical only; Kiambu preserves the EVAL/FOLLOWUP behavior after renumbering to Q7–Q16
- Strict evaluation JSON parsing
- Score / `missing_points` / `followup_needed` validation
- Low-score normalization when `followup_needed` is absent but valid unresolved points are present
- Step-2 admission uses extracted follow-up candidates rather than raw generation text
- Follow-up capacity and duplicate normalization
- Structured component IDs with deterministic ID -> text mapping
- Run/survey ownership and cancellation isolation
- Fail-closed behavior for malformed or contradictory evaluation output

### V2 artifact identity and recording association — integrated implementation baseline

- [#155](https://github.com/ishizuki-tech/Survey2026/issues/155) completed the clean-install **V2-only** artifact protocol: canonical UUIDv7 survey identity, filenames/dates based on the UTC survey start encoded in that UUID, and explicit destination-relative paths under the selected export repository root (not a new leading `exports/` folder).
- `V2ArtifactPaths` builds `YYYY-MM-DD/survey/<StartUTC>_survey_<Device>_<SurveyUUIDv7>.json`, `YYYY-MM-DD/voice/<StartUTC>_voice_<Device>_<SurveyUUIDv7>_<QuestionID>_<RecordingID>.wav` and typed diagnostics under `YYYY-MM-DD/diagnostics/{survey,whisper,slm,logcat,runtime,crash}/`.
- [#138](https://github.com/ishizuki-tech/Survey2026/issues/138) completed per-recording WAV preservation and immutable association with the exact question/follow-up and Whisper transcript, including multiple recordings for a single node. Edited answer text is not the original transcription reference.
- V2 JSON success does not, by itself, prove all referenced WAV files were delivered. Verify individual upload/recovery references before stating a recording retention result.
- V1 uploaded artifacts and the historical in-place update logs are retained as evidence, not silently migrated into V2. Further field validation needs the exact V2 APK/protocol/device/recording identifiers.

### UI/navigation and session policy baseline

- Focused UI/recording/navigation changes were integrated through completed #159 (unfinished Exit/session behavior), #164 (Whisper transcribing navigation lock), #167 (SLM processing navigation lock), #169 (recording/rotation lifecycle) and #178 (Transcribing status). Completion of implementation does not substitute for current-device UX and lifecycle-soak acceptance.
- Product #39 settled the policy that **mic permission is optional for typed survey completion**; open engineering #124 owns implementation and acceptance. Open #121 separately owns **Continue Without Voice** disabling session TTS. Do not report either as accepted product behavior until tests establish it.

### Native speech baseline

- On-device whisper.cpp JNI integration
- whisper.cpp pinned to v1.9.3
- Bundled baseline model: `models/ggml-small-q5_1.bin`

### Release / CI baseline

- Pushes to `main` build the signed release APK and publish the production GitHub Release / Download Page metadata
- Main release titles use `MAIN #<run> · <short-sha>`
- Release metadata publishes source SHA, APK SHA-256, signing certificate SHA-256, English/Swahili config files and hashes, and dynamic "What's New"
- Manual release publication validates the requested stable tag against the source commit
- `gh-pages/latest.json` is generated from the release pipeline and is the authoritative machine-readable pointer to the current production release
- Branch pushes run the branch preview pipeline
- Every successful branch preview is retained as its own GitHub prerelease with a unique branch/SHA/run tag
- Branch preview titles use `PREVIEW #<run> · <branch> · <short-sha>`
- Branch prereleases include the APK and an optional QR PNG
- Release signing key is separate from Android debug signing
- Local release builds are not forcibly signed with the Android debug key
- The production release keystore certificate matches the published release signer

---

## Workstream boundaries and planning ownership

The Roadmap Project owns **live** Priority / Status / Area; GitHub Issues own active scope/acceptance criteria; [TODO.md](TODO.md) lists current unfinished concrete tasks. The Project fields were not accessible at the time of this documentation update, so no live Priority/Status/Area is copied here. Do not preserve a historic table with closed issues as though they are active Project work.

### Closed, durable planning outcomes

- **#37 — Distribution/provenance:** signed main-release and branch-preview publication, release metadata and the machine-readable `gh-pages/latest.json` form the established distribution workflow. Verify the current workflow and actual artifacts for any claim about a particular external parity monitor.
- **#39 — Microphone policy:** typed completion after denied mic permission is the approved product contract; open #124 still owns the engineering and acceptance work.
- **#40 — Data-handling contract:** completed as a documentation/planning issue. Confirm exact operational retention/access/deletion wording against the canonical policy rather than inferring new behavior from closure.
- **#41 — AI semantic evaluation:** broad historical tracking was consolidated/closed; open #98–#101 separately define remaining real-model semantic and language-parity evidence.
- **#42 — Speech quality benchmarking:** parent tracking closed; future model selection must remain evidence-based against the actual device/protocol/model baseline.
- **#51 — S25 LiteRT-LM runtime footprint:** earlier investigation tracked and closed; no blanket device/GPU-crash certification or production CPU migration follows from closure.
- **#69 — UI/UX umbrella:** earlier redesign tracking closed; focused changes and acceptance gaps continue under the appropriate Issues.

### Active issue families (references, not live Project status)

- **Survey correctness/lifecycle:** [#118](https://github.com/ishizuki-tech/Survey2026/issues/118), [#119](https://github.com/ishizuki-tech/Survey2026/issues/119), [#121](https://github.com/ishizuki-tech/Survey2026/issues/121), [#124](https://github.com/ishizuki-tech/Survey2026/issues/124). Preserve survey route ownership, runtime cleanup, TTS-session behavior and permission-independent typed input as distinct concerns.
- **Model import and AI semantics:** [#120](https://github.com/ishizuki-tech/Survey2026/issues/120), [#123](https://github.com/ishizuki-tech/Survey2026/issues/123), [#98](https://github.com/ishizuki-tech/Survey2026/issues/98), [#99](https://github.com/ishizuki-tech/Survey2026/issues/99), [#100](https://github.com/ishizuki-tech/Survey2026/issues/100), [#101](https://github.com/ishizuki-tech/Survey2026/issues/101). Separate invalid/racing model acquisition from EVAL/FOLLOWUP semantic quality.
- **Field acceptance and documentation:** [#43](https://github.com/ishizuki-tech/Survey2026/issues/43), [#44](https://github.com/ishizuki-tech/Survey2026/issues/44), [#45](https://github.com/ishizuki-tech/Survey2026/issues/45), [#47](https://github.com/ishizuki-tech/Survey2026/issues/47). Treat target-device UX, lifecycle reliability, supported device/ABI policy and documentation audit as distinct validation outcomes.

---

## Historical Validation Matrix (pre-V2 and initial Kiambu acceptance)

These results apply to the **specific builds, devices and protocol described in the archived evidence**. They must not be reused as fresh V2 acceptance, universal device certification or closure of open #43–#45 and #98–#101. Current targeted test coverage is tracked in the corresponding GitHub Issues.

| Area | JVM | Instrumentation | Real Device | Release/CI |
| --- | --- | --- | --- | --- |
| Finalization/upload logic | Yes | Partial | Samsung SM-S731U PASS | Main/branch build coverage |
| Pending discovery/reconciliation | Yes | Partial | Samsung SM-S731U PASS | In main |
| Reboot recovery | Yes | Partial | Samsung SM-S731U durable PASS | N/A |
| Duplicate suppression | Yes | Partial | Samsung SM-S731U durable PASS | N/A |
| Genuine signed release-to-release update | N/A | N/A | Samsung SM-S731U durable PASS, Build #109 -> #110 | In-place signed update; `MY_PACKAGE_REPLACED`, same-UUID upload, and two no-resubmit checks retained |
| Release provenance | N/A | N/A | Historical artifact identity and signed-update baseline PASS | Config hashes + What's New present in the historical workflow; verify any current parity implementation before claiming a pass |
| Follow-up deterministic policy | Yes | Scripted coverage exists | Semantic validation pending | N/A |
| Kiambu INFO/Consent/STOP navigation | Yes | Build coverage | Samsung SM-S731U full EN/SW acceptance PASS | Integrated through PR #53 |
| Whisper integration | Build coverage | Limited | Benchmark pending | Release assembly |
| Microphone denial | Historical acceptance not established | Historical acceptance not established | Pending typed-only acceptance in #124 | N/A |

---

## Deferred Work / Non-goals

- Issues #1-#8 remain outside Project #2 and are not modified as part of roadmap maintenance.
- Do not revise the accepted completed-survey Review -> Finish -> Done flow as incidental documentation maintenance. Incomplete-session Exit behavior was addressed separately in #159 and must be checked against current code rather than assumed to be absent.
- Do not introduce extra Done-screen upload controls through documentation changes.
- No interviewer-ID system while the product assumption remains one interviewer per device.
- No speculative SLM runtime redesign before semantic evaluation identifies a concrete failure.
- No Swahili production-model switch based only on branch-preview or Mac timing.
