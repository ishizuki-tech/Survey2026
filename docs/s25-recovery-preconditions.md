# Galaxy S25 production-package recovery — preconditions

**Status**: Deferred pending fresh, read-only device-state verification
**Relevant issues**: #103 (closed), #71 (closed historical signed-update evidence), #89 (open)
**LiteRT-LM baseline**: 0.18.0

---

## Background

Issue #103 completed the LiteRT-LM 0.18.0 upgrade without authorizing a new
production-package recovery exercise on Galaxy S25 (SM-S731U). Issue #71 records a
separate, historical release-signed Build #109 to #110 in-place update that preserved a
pending survey, delivered `MY_PACKAGE_REPLACED`, reconciled the upload, and avoided a
duplicate resend. That historical result is not proof of this device's current package,
signature, version, or pending-data state.

An earlier report described an accidentally installed debug-signed
`com.negi.survey` build on this S25. Treat that only as historical evidence. Do not
assume that the package is still installed, that it still has that signing identity, or
that its data can safely be preserved without a fresh read-only assessment.

## Package roles

- `com.negi.survey` is the production package. Its signing identity and existing app data
  are recovery-critical.
- `com.negi.survey.local` is an isolated local-validation package. Its tests do not prove
  recovery, signing compatibility, or data preservation for `com.negi.survey`.
- A `localBuild` debug APK is not production-signed.

---

## What must happen before clean production validation on S25

1. **Obtain current read-only evidence.** Confirm the installed production package identity
   and versionCode, its actual installed signing identity using a valid package-signing
   inspection, pending survey/upload records, and whether data preservation is feasible.
   Do not infer signing identity from an APK filename, an older report, or a local-package
   installation.

2. **Verify the candidate APK separately.** Use the Android build-tools command below for
   the candidate production APK:

   ```sh
   apksigner verify --print-certs <production-apk>
   ```

   Compare the reported certificate SHA-256 with the independently established production
   signing-certificate SHA-256 recorded in Issue #71. `aapt2 dump badging` and
   `adb shell pm list packages` are useful for package metadata and paths, but neither
   verifies an APK signing certificate. Package name and versionCode are separate checks;
   a candidate APK certificate alone does not establish the installed device package's
   signing identity.

3. **Confirm update compatibility.** A genuine signed update requires a compatible signing
   identity, appropriate versionCode progression, and confirmation that survey data can be
   preserved. If no safe signed replacement is available, or the signing certificates differ,
   stop and request a separately approved recovery plan. Do not uninstall, downgrade, clear
   data, or otherwise bypass Android signing protection.

4. **MY_PACKAGE_REPLACED recovery scenario (validation step).** Only after the
   preconditions above are met and explicit approval is granted:
   - Use production-signed `com.negi.survey` build N, create a pending survey/upload record,
     and preserve its identity before the update.
   - Verify the candidate production APK with `apksigner verify --print-certs` and confirm its
     compatible signature and higher versionCode.
   - Install build N+1 with the correct ADB syntax:

     ```sh
     adb -s R5GL2118ZGD install -r <apk>
     ```

   - Confirm actual `MY_PACKAGE_REPLACED` delivery, WorkManager reconciliation, survival of
     pending survey/upload records, eventual upload after network restoration, and no duplicate
     resend after restart.

PR #113 adds JVM coverage for `UploadRescheduleReceiver.isRelevantAction()`. Those tests
classify Intent actions only; they do not establish broadcast delivery, real-device
WorkManager reconciliation, production-signed update recovery, or preservation of pending
uploads across a package replacement. Issue #71 is the historical signed-update evidence;
it does not remove the need for the current-device preconditions above.

---

## What is NOT blocked

- CI validation on emulator (JVM unit tests, debug APK build)
- Unit and integration tests not requiring the S25 device
- Ongoing LiteRT-LM model evaluation on other devices
- All existing JVM test coverage for upload recovery logic

---

## Model-investigation status

Issue #89 remains open. Qwen3 1.7B INT4 was investigated on 2026-10-14 and is **NO-GO for
production adoption**: the `EN_Q15_COMPLETE` EVAL produced a noncanonical component ID, and
the strict policy correctly rejected it. Further Qwen inference requires separate safety
approval. Gemma 3n E4B int4 remains the production model. Refer to Issue #89 for the current
E2B and LFM2.5 investigation findings rather than duplicating candidate details here.

---

## References

- Issue #103: LiteRT-LM 0.18.0 upgrade and recovery-precondition context
- Issue #71: historical release-signed `MY_PACKAGE_REPLACED` recovery validation
- Issue #89: lower-memory model investigation and Qwen3 NO-GO evidence
- PR #113: `isRelevantAction()` Intent-classification tests
