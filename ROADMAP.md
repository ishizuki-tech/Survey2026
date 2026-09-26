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
- Pixel 9a / Android 16 remains the manually validated baseline for the core survey/upload flow
- Core upload/finalization/recovery behavior remains the production baseline unless new regression evidence appears

### Active Kiambu validation branch — not merged

- Branch: `feature/kiambu-introduction-consent`
- Kiambu implementation baseline: `1240cec`
- Latest production `main` incorporated into that implementation baseline: `c7d668a`
- Implementation preview verified by CI: `PREVIEW #71 · feature/kiambu-introduction-consent · 1240cec`
- Later commits on the same branch may update documentation without changing the Kiambu implementation baseline
- Questionnaire migrated to Kiambu Q1-Q16 in both English and Swahili configs
- Questionnaire Introduction is a config-driven INFO node with Android TTS read-aloud behavior
- Consent is config-driven; decline routes to a dedicated STOP / ConsentDeclined terminal screen
- Navigation/config unit coverage includes INFO, Consent, STOP, and language-specific routes
- Manual English consent validation has observed Yes -> Q1 and No -> STOP
- Swahili physical-device path and full Q1-Q16 acceptance remain pending
- The branch remains separate from `main` until Kiambu acceptance is complete

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

Real-device baseline:
- Pixel 9a / Android 16
- Offline pending survey recovery: PASS
- Network restore and remote upload: PASS
- Reboot recovery: PASS
- App-update recovery: PASS
- Duplicate suppression: PASS
- Post-success cleanup / no duplicate submit: PASS

Known validation limitation:
- App-side `LOCKED_BOOT_COMPLETED` handling has not been directly evidenced in logs. This remains an observation point if that path becomes part of the supported-device contract.

### AI / follow-up deterministic baseline

- TWO_STEP path active for the shipped main questionnaire
- Main release #95 uses the pre-Kiambu Q8-Q17 numbering
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
- `gh-pages/latest.json` is generated from the release pipeline and currently points to release #95
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
| P0 | #36 | Kiambu questionnaire acceptance |
| P0 | #37 | Stable release provenance and distribution |
| P0 | #38 | Field verification evidence |
| P0 | #39 | Microphone-denial product behavior |
| P0 | #40 | Data-handling contract |
| P1 | #41 | AI follow-up quality and semantic acceptance |
| P1 | #42 | Speech recognition quality benchmark |
| P1 | #43 | Voice and microphone UX validation |
| P2 | #44 | Reliability and lifecycle soak testing |
| P2 | #45 | Supported-device and ABI compatibility |
| P2 | #46 | Release, CI, and Download Page hardening |
| P3 | #47 | Documentation, toolchain, and repository maintenance |

### P0 — Field Deployment Readiness

**#36 Kiambu questionnaire acceptance**

Outcome:
- English and Swahili Kiambu flows are accepted on target hardware.
- Consent accept/reject paths, Q6 screen-out, and Introduction/Consent TTS are verified.
- Remaining source-fidelity decisions are explicit.
- The branch is ready for a separately reviewed PR into `main`.

**#37 Stable release provenance and distribution**

Outcome:
- Field operators can identify the exact released APK/configs and their hashes.
- Verified-device / deployment-status information is visible.
- GitHub Release, rendered Download Page, and `latest.json` are automatically checked for parity.

**#38 Field verification evidence**

Outcome:
- Manual Pixel 9a validation is converted into durable repository evidence with device/build identity and upload/recovery proof.

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

### P2 — Reliability / Compatibility / CI

**#44 Reliability and lifecycle soak testing**

Outcome:
- Cancellation, rotation, background/foreground, restart, update, network flapping, low storage, and long-session behavior are reproducibly exercised.

**#45 Supported-device and ABI compatibility**

Outcome:
- Supported device/ABI policy exists before Samsung Galaxy S25 validation is treated as production evidence.

**#46 Release, CI, and Download Page hardening**

Outcome:
- The existing production/preview pipelines are documented and maintainable.
- Local release-signing procedure is documented without secrets.
- Main Release/Pages behavior and retained branch-preview behavior are documented.

Field-release parity and verified-device metadata are owned by P0 issue #37, not duplicated here.

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
| Finalization/upload logic | Yes | Partial | Pixel 9a PASS | Main/branch build coverage |
| Pending discovery/reconciliation | Yes | Partial | Pixel 9a PASS | In main |
| Reboot/update recovery | Yes | Partial | Pixel 9a PASS | N/A |
| Duplicate suppression | Yes | Partial | Pixel 9a PASS | N/A |
| Release signing/update path | N/A | N/A | Pixel 9a PASS | Signed release pipeline |
| Release provenance | N/A | N/A | Install/update baseline PASS | Config hashes + What's New complete; automated parity check pending |
| Follow-up deterministic policy | Yes | Scripted coverage exists | Semantic validation pending | N/A |
| Kiambu INFO/Consent/STOP navigation | Yes | Build coverage | English consent paths observed; full EN/SW pass pending | Implementation preview #71 PASS |
| Whisper integration | Build coverage | Limited | Benchmark pending | Release assembly |
| Microphone denial | Pending | Pending | Pending | N/A |

---

## Deferred Work / Non-goals

- Do not merge the current Kiambu branch into `main` until its acceptance work is complete.
- Issues #1-#8 remain outside Project #2 and are not modified as part of roadmap maintenance.
- No redesign of Review -> Finish -> Done without regression evidence.
- No Exit-button upload path; Done screen remains free of survey-upload actions.
- No interviewer-ID system while the product assumption remains one interviewer per device.
- No speculative SLM runtime redesign before semantic evaluation identifies a concrete failure.
- No Swahili production-model switch based only on branch-preview or Mac timing.
