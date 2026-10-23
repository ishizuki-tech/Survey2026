# Galaxy S25 production-package recovery — preconditions

**Status**: Deferred (accepted risk, not waived)
**Relevant issues**: #103 (closed 2026-10-14), #89 (open)
**LiteRT-LM baseline**: 0.18.0 (merged via PR #104, commit b20b03f)

---

## Background

Issue #103 was closed after upgrading LiteRT-LM from 0.17.1 to 0.18.0. The closing decision
explicitly accepted the known risk of GPU memory pressure on Galaxy S25 (SM-S731U) but
deferred — and did not waive — production-package recovery validation on that device.

The deferral is required because the Galaxy S25 currently has an accidentally installed
debug-signed `com.negi.survey` 0.18.0 build. Using this device for clean production
validation without first resolving that package state would produce unreliable results.

---

## What must happen before clean production validation on S25

1. **Device package restoration (requires explicit approval)**
   The accidentally installed debug-signed `com.negi.survey` 0.18.0 build must be replaced
   with a production-signed package via a data-preserving method approved explicitly by NEGI.
   Do not delete app data, uninstall, or use `-d` (downgrade) without a separate decision.

2. **Pre-replacement assessment of MY_PACKAGE_REPLACED recovery**
   Before executing the replacement, assess whether any pending survey uploads exist on the
   device. If pending uploads are present, confirm they will survive the `MY_PACKAGE_REPLACED`
   broadcast and WorkManager reconciliation before proceeding.
   - `UploadRescheduleReceiver.isRelevantAction("android.intent.action.MY_PACKAGE_REPLACED")`
     now has unit test coverage via `UploadRescheduleReceiverIntentClassificationTest`.
   - The upload recovery path (`SurveyUploadRescheduler` → `SurveyUploadWork.reconcile()`)
     is covered by JVM unit tests; no direct coupling between LiteRT-LM and the recovery
     path was found in the codebase.

3. **Post-replacement verification**
   After the replacement:
   - Verify survey records are intact (no loss of pending uploads).
   - Verify the installed package is production-signed: check via `aapt2 dump badging` and
     `adb shell pm list packages -f com.negi.survey`.
   - Verify package identity using `adb shell pm list packages --show-versioncode`.

4. **MY_PACKAGE_REPLACED recovery scenario (validation step)**
   After the device is in a known-good state, run the signed-update recovery scenario:
   - Install build N (production-signed `com.negi.survey.local` via `-PlocalBuild=true`).
   - Complete a survey to produce a pending JSON.
   - Install build N+1 as an update (`adb install -r -s R5GL2118ZGD <apk>`).
   - Confirm `MY_PACKAGE_REPLACED` receiver fired and survey upload was re-queued:
     `adb logcat -d | grep "MY_PACKAGE_REPLACED\|recoverPending\|reconciled"`
   - Confirm counts: `reconciled=1, duplicates=0, operationalFailures=0`.
   - Restore network, confirm upload success, confirm no re-submit on restart.

---

## What is NOT blocked

- CI validation on emulator (JVM unit tests, debug APK build)
- Unit and integration tests not requiring the S25 device
- Ongoing LiteRT-LM model evaluation on other devices
- All existing JVM test coverage for upload recovery logic

---

## Memory pressure note (from #89)

E4B GPU runs on Galaxy S25 cause severe LMKD reclamation of unrelated background
processes. This was observed with both LiteRT-LM 0.17.1 and 0.18.0; no 0.18.0-specific
material regression was confirmed from available measurements (single noisy process-cold
pair). Swap PSS increased approximately 11.7 MiB after init and 17.3 MiB after inference
for 0.18.0 versus 0.17.1.

The 0.18.0 benchmark was safety-stopped after normal teardown. Do not rerun high-pressure
E4B tests on this S25 without a new safety plan approved by NEGI.

Issue #89 tracks the lower-memory model investigation:
- **E2B**: NO-GO. Swahili EVAL/FOLLOWUP semantics below E4B baseline (16/30 vs 25/30
  expected policy decisions). A compact EVAL protocol experiment also failed (0/90 outputs
  parse-valid).
- **LFM2.5 1.2B INT4**: GO for continued investigation only; NO-GO for production. GPU
  execution confirmed (542/542 nodes on LITERT_CL delegate); first English EVAL fixture
  (EN_Q15_COMPLETE) rejected by the strict parser due to a noncanonical component ID.
- **Qwen3 1.7B**: Next candidate. No investigation results recorded as of 2026-10-22.
- **Production model**: Gemma 3n E4B int4 remains the production model until a lower-memory
  candidate passes the semantic acceptance matrix.

---

## References

- Issue #103 (closed): LiteRT-LM 0.18.0 upgrade risk acceptance and S25 recovery deferral
- Issue #89 (open): Galaxy S25 LiteRT-LM memory pressure / lower-memory model investigation
- `ROADMAP.md` — Validation Matrix, S25 MY_PACKAGE_REPLACED PASS (build #109 → #110,
  with earlier LiteRT-LM version)
- `docs/field-verification-evidence.md` — Upload recovery field evidence baseline
