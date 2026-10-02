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
- Core upload/finalization/recovery behavior remains the production baseline unless new regression evidence appears.

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

Field-evidence baseline (Issue #38 complete; see
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
  The worker returned SUCCESS and two later recovery runs each reported zero
  discovered, reconciled, duplicate, and operational-failure counts.
- A diagnostic logcat worker separately recorded a FileNotFoundException,
  retry, and failure after another worker had already uploaded that diagnostic
  artifact. This is separate from mandatory survey JSON release-update
  acceptance; no root cause is asserted here.

Known validation limitation:
- App-side `LOCKED_BOOT_COMPLETED` handling has not been directly evidenced in logs. This remains an observation point if that path becomes part of the supported-device contract.

### AI / follow-up deterministic baseline

- TWO_STEP path active for the shipped main questionnaire
- The current pre-Kiambu main questionnaire uses Q8-Q17 numbering
- Kiambu implementation preserves the same AI flow after renumbering the AI questionnaire section to Q7-Q16
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
- `gh-pages/latest.json` is generated from the release pipeline and is the authoritative machine-readable pointer to the current production release
- Branch pushes run the branch preview pipeline
- Every successful branch preview is retained as its own GitHub prerelease with a unique branch/SHA/run tag
- Branch preview titles use `PREVIEW #<run> · <branch> · <short-sha>`
- Branch prereleases include the APK and an optional QR PNG
- Release signing key is separate from Android debug signing
- Local release builds are not forcibly signed with the Android debug key
- The production release keystore certificate matches the published release signer

---

## Project #2 Workstreams

Project #2 is the authoritative live execution board for these workstreams. Priority, Area, and Status should be maintained there.

| Priority | Issue | Workstream |
| --- | --- | --- |
| P0 | #37 | Stable release provenance and distribution |
| P0 | #39 | Microphone-denial product behavior |
| P0 | #40 | Data-handling contract |
| P1 | #41 | AI follow-up quality and semantic acceptance |
| P1 | #42 | Speech recognition quality benchmark |
| P1 | #43 | Voice and microphone UX validation |
| P1 | #51 | Galaxy S25 LiteRT-LM memory pressure / lifecycle tradeoff |
| P2 | #44 | Reliability and lifecycle soak testing |
| P2 | #45 | Supported-device and ABI compatibility |
| P2 | #69 | Survey2026 UI/UX redesign |
| P3 | #47 | Documentation, toolchain, and repository maintenance |

### P0 — Field Deployment Readiness

**#37 Stable release provenance and distribution**

Outcome:
- Field operators can identify the exact released APK/configs and their hashes.
- Verified-device / deployment-status information is visible.
- GitHub Release, rendered Download Page, and `latest.json` are automatically checked for parity.

**#39 Microphone-denial product behavior**

Outcome:
- Text-only versus mandatory-microphone behavior is explicit, tested, and documented.

**#40 Data-handling contract**

Outcome:
- Storage, upload destinations, retention, deletion, recovery identity, and operator access are documented for survey JSON, WAV, logs, models, and hashed device tags.

### P1 — Quality

**#41 AI follow-up quality and semantic acceptance**

Outcome:
- Kiambu Q7-Q16 English/Swahili prompt fixtures are auditable.
- Real-model semantic behavior is measured on target hardware.
- Deterministic app-side guards remain stable unless evidence requires a change.

**#42 Speech recognition quality benchmark**

Outcome:
- CER/WER comparison is reproducible with immutable references and a defined speaker/device/noise/distance/rate matrix.
- Production Swahili model selection is evidence-based.

**#43 Voice and microphone UX validation**

Outcome:
- Capture -> WAV -> Whisper -> answer -> SLM -> TTS ownership is documented and user-visible states are verified.

**#51 Galaxy S25 LiteRT-LM memory pressure / lifecycle tradeoff**

Outcome:
- Evidence-backed lower-memory/runtime/lifecycle options for the large
  LiteRT-LM GPU-resident footprint are evaluated on S25.
- Production is not blindly switched to CPU and unrelated runtime refactoring
  is avoided.

### P2 — Reliability / Compatibility / CI

**#44 Reliability and lifecycle soak testing**

Outcome:
- Cancellation, rotation, background/foreground, restart, update, network flapping, low storage, and long-session behavior are reproducibly exercised.

**#45 Supported-device and ABI compatibility**

Outcome:
- Supported device/ABI policy exists before Samsung Galaxy S25 validation is treated as production evidence.

**#69 Survey2026 UI/UX redesign**

Outcome:
- Survey layout, navigation, interaction flow, and field usability are
  redesigned on an isolated workstream.
- The redesign does not redefine SLM/LiteRT-LM or ASR/Whisper engine
  boundaries; any non-UI behavior change is tracked separately.

### P3 — Documentation / Maintenance

**#47 Documentation, toolchain, and repository maintenance**

Outcome:
- README and technical documentation match the actual repository.
- Native/SLM/Whisper/storage/diagnostics/config/test-tier ownership is documented.
- Toolchain, generated-output, model-binary, and secret-handling rules stay explicit.

---

## Validation Matrix

| Area | JVM | Instrumentation | Real Device | Release/CI |
| --- | --- | --- | --- | --- |
| Finalization/upload logic | Yes | Partial | Samsung SM-S731U PASS | Main/branch build coverage |
| Pending discovery/reconciliation | Yes | Partial | Samsung SM-S731U PASS | In main |
| Reboot recovery | Yes | Partial | Samsung SM-S731U durable PASS | N/A |
| Duplicate suppression | Yes | Partial | Samsung SM-S731U durable PASS | N/A |
| Genuine signed release-to-release update | N/A | N/A | Samsung SM-S731U durable PASS, Build #109 -> #110 | In-place signed update; `MY_PACKAGE_REPLACED`, same-UUID upload, and two no-resubmit checks retained |
| Release provenance | N/A | N/A | Artifact identity and signed-update baseline PASS | Config hashes + What's New complete; automated parity check pending |
| Follow-up deterministic policy | Yes | Scripted coverage exists | Semantic validation pending | N/A |
| Kiambu INFO/Consent/STOP navigation | Yes | Build coverage | Samsung SM-S731U full EN/SW acceptance PASS | Integrated through PR #53 |
| Whisper integration | Build coverage | Limited | Benchmark pending | Release assembly |
| Microphone denial | Pending | Pending | Pending | N/A |

---

## Deferred Work / Non-goals

- Issues #1-#8 remain outside Project #2 and are not modified as part of roadmap maintenance.
- No redesign of Review -> Finish -> Done without regression evidence.
- No Exit-button upload path; Done screen remains free of survey-upload actions.
- No interviewer-ID system while the product assumption remains one interviewer per device.
- No speculative SLM runtime redesign before semantic evaluation identifies a concrete failure.
- No Swahili production-model switch based only on branch-preview or Mac timing.
