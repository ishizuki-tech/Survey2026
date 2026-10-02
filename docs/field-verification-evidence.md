# Issue #38 field-verification evidence

## Purpose and result labels

This document consolidates the latest independent field-evidence review for
Issue #38. It distinguishes evidence of an observed outcome from proof of the
application mechanism that produced that outcome.

Result labels used below:

- **PASS — DURABLE EVIDENCE**: retained remote artifacts and/or terminal upload
  records directly support the stated outcome.
- **PASS — PARTIAL EVIDENCE**: a relevant trigger or related outcome was
  observed, but the complete causal chain was not retained.
- **NOT EVIDENCED**: the required chain was not present in the reviewed data.
- **FAIL**: the reviewed retained artifacts contradict the expected outcome.

## Evidence sources and scope

The evidence source was a clean local clone of the `SurveyExports` remote. On
2026-10-01, its checked-out `main` and read-only `git ls-remote origin HEAD`
both identified remote HEAD as:

```text
e097523ad56e076eb1c931404f1f48d8370d1960
```

The reviewed remote-export date scope is **2026-09-29 through 2026-10-01**.
This is remote artifact evidence, not a claim that an Android device test was
rerun during this documentation update.

Issue #38 is closed as completed. The remaining genuine release-to-release APK
update / `MY_PACKAGE_REPLACED` acceptance activity is tracked separately by
Issue #71, **[Engineering] Verify release-signed app update recovery end to
end**. The current release and the installed device were both Build #109
(`ba67468`), versionCode 109. With no higher-version release APK available,
there was no genuine update to test; a same-version reinstall is not evidence
of a real release update.

| Date | Final survey JSON | UUID-bearing diagnostic logcats | Retained WAV files |
| --- | ---: | ---: | ---: |
| 2026-09-29 | 8 | 8 | 29 |
| 2026-09-30 | 9 | 9 | 13 |
| 2026-10-01 | 2 | 2 | 0 |
| **Total** | **19** | **19** | **42** |

All 19 final JSON artifacts have distinct survey UUIDs and a matching
UUID-bearing remote logcat. The decompressed logcat session metadata agrees
with the JSON `survey_id` for all 19; no conflicting duplicate final JSON was
found in the scoped export tree. The dataset includes 12 `SM-S931U1` and 7
`SM-S731U` artifacts, across 11 recorded short build identities.

## Durable online-upload example

The following example provides a complete retained remote-artifact and upload
trace for one final survey:

| Field | Evidence |
| --- | --- |
| Survey UUID | `fa692d45-f978-416c-9cc7-b6274ae5208c` |
| Device / API | Samsung `SM-S931U1`, API 36 |
| Application version | `versionName=0.0.1`, `versionCode=99` |
| Build identity | `a7696b2` |
| Final JSON | `2026-09-29/exports/2026-09-29_11-40-03_survey_SM-S931U1_F757B79A4E33_fa692d45-f978-416c-9cc7-b6274ae5208c.json` |
| Matching logcat | `2026-09-29/diagnostics/logcat/logcat_2026-09-29_11-40-03_pid13035_fa692d45.log.gz` |
| Submission record | The matching logcat records `GitHubUploader uploadStream` submission of that exact final-JSON path. |
| Terminal record | A later retained logcat records `uploadStream: done` for the same path, with remote blob SHA `ed1a8d00e28add777f764beaa147782ae6319a85`. |

This supports a successful online final-JSON upload for that survey with
durable evidence. It is not evidence of a recovery upload, because the trace
does not show a pending-offline state or a recovery trigger.

## Physical-device recovery evidence

The following recovery results were verified on Samsung `SM-S731U`, Android
API 36. They supplement, rather than replace, the historical remote snapshot
inventory above.

| Scenario | Survey UUID | Result | Retained evidence |
| --- | --- | --- | --- |
| Offline pending preservation | `b45a4e1e-a943-4f60-b6e0-bb745bcbd5dc` | **PASS — DURABLE EVIDENCE** | The survey remained pending while offline. |
| Process restart / startup recovery | `b45a4e1e-a943-4f60-b6e0-bb745bcbd5dc` | **PASS — DURABLE EVIDENCE** | Restart recovery preserved and rediscovered the pending survey. |
| Reconnect automatic upload | `b45a4e1e-a943-4f60-b6e0-bb745bcbd5dc` | **PASS — DURABLE EVIDENCE** | Restored network connectivity led to automatic final-JSON upload. |
| Canonical remote JSON and no-resubmit result | `b45a4e1e-a943-4f60-b6e0-bb745bcbd5dc` | **PASS — DURABLE EVIDENCE** | The same survey UUID reached its canonical remote JSON outcome, and a later recovery scan recorded post-success `discovered=0`. |
| Reboot recovery | `4c69a025-b6f0-437e-9710-ca91e2c3ade9` | **PASS — DURABLE EVIDENCE** | A real `adb reboot` delivered `BOOT_COMPLETED`, rediscovered the retained pending artifact, then uploaded the canonical JSON after connectivity returned. |

For reboot recovery, the application logged:

```text
Survey recovery action=android.intent.action.BOOT_COMPLETED discovered=1 reconciled=1 duplicates=0 unclassified=1 operationalFailures=0 classifications={ACTIVE=1}
Found pending: dir=/data/user/0/com.negi.survey/files/pending_uploads files=2
```

The pending artifact retained the short UUID `4c69a025`. After connectivity
returned, the canonical final JSON was uploaded to:

```text
2026-10-01/exports/2026-10-01_18-43-34_survey_SM-S731U_414A86B07D91_4c69a025-b6f0-437e-9710-ca91e2c3ade9.json
```

The upload completed with blob SHA
`a3a2c81da244dc90c0c0b295344b9a4bdfb59c03` and `Worker result SUCCESS`.

## Scoped one-JSON outcome and duplicate-suppression boundary

For the 19 final surveys in scope, there is one retained final JSON per survey
UUID, 19 distinct UUIDs, and no conflicting duplicate final JSON. This is a
**PASS — DURABLE EVIDENCE** for the scoped remote one-JSON outcome.

The historical remote outcome alone would not prove the app-side
duplicate-suppression mechanism. The later physical-device evidence for
`b45a4e1e-a943-4f60-b6e0-bb745bcbd5dc` adds the same-UUID canonical outcome
and a post-success `discovered=0` recovery result. Taken together, the scoped
remote inventory and that retained recovery evidence support duplicate
suppression as **PASS — DURABLE EVIDENCE**.

## Release-update boundary

The current implementation centralizes finalization and recovery through
`SurveyUploadWork.reconcile(...)`; recovery is initiated by the
rescheduler/receiver path. The physical-device evidence now covers offline
pending preservation, startup/process restart, reconnect upload, reboot
recovery, canonical remote JSON, duplicate/no-resubmit outcome, and successful
worker completion.

The one intentionally deferred scenario is a genuine signed
release-to-release APK update that delivers `MY_PACKAGE_REPLACED`. It belongs
to Issue #71 because Build #109 / versionCode 109 was already installed and
was also the latest published release; no newer release existed to exercise an
actual update. Same-version reinstall behavior must not be used to close that
gap.

`LOCKED_BOOT_COMPLETED` remains not required unless a supported-device policy
later makes it an acceptance requirement.

## Artifact-integrity evidence

### Voice artifacts

The scoped final JSON records reference 49 voice artifacts. Only 42 matching
remote WAV files are retained. The seven missing artifacts are:

| Survey UUID | Missing question recordings |
| --- | --- |
| `53f2a99e-f79e-46c0-8631-184b9b909b86` | Q15 |
| `b1cb3941-7470-4162-9a35-b06f7375b821` | Q7, Q13, Q15, Q16 |
| `e253882b-a121-4ae8-a92f-36e35844aba9` | Q10, Q14 |

For each missing item, the matching local/export diagnostic evidence records
voice export and remote upload submission, but no retained completion record
or remote WAV artifact was found. This is:

**FAIL — optional voice artifact retention/publication.**

This failure is separate from the mandatory final survey JSON: it must not be
used as proof that final-JSON recovery failed.

### Diagnostic artifacts

All 19 final JSON artifacts have matching remote UUID-bearing logcats. This is
useful correlation evidence, but there is no expected-artifact manifest against
which to establish completeness for every diagnostic artifact type. Diagnostic
artifact integrity is therefore **PASS — PARTIAL EVIDENCE**.

## Final acceptance matrix

| Acceptance item | Result | Evidence boundary |
| --- | --- | --- |
| Online upload | **PASS — DURABLE EVIDENCE** | Retained final JSON and terminal upload trace, including the `fa692d45-...` example above. |
| Offline pending recovery | **PASS — DURABLE EVIDENCE** | `b45a4e1e-...` remained pending while offline. |
| Reconnect automatic upload | **PASS — DURABLE EVIDENCE** | `b45a4e1e-...` automatically uploaded after connectivity returned. |
| Startup / process-restart recovery | **PASS — DURABLE EVIDENCE** | `b45a4e1e-...` was preserved and rediscovered across process restart. |
| Reboot recovery | **PASS — DURABLE EVIDENCE** | A real reboot produced `BOOT_COMPLETED`, `discovered=1`, reconciliation, and later successful canonical upload for `4c69a025-...`. |
| `MY_PACKAGE_REPLACED` release update | **DEFERRED TO ISSUE #71** | No genuine higher-version signed release APK was available: installed and latest published Build #109 were both versionCode 109. Same-version reinstall is not update evidence. |
| Duplicate suppression | **PASS — DURABLE EVIDENCE** | The verified same-UUID canonical outcome and scoped 19-UUID remote inventory show no conflicting final JSON. |
| Post-success `discovered=0` | **PASS — DURABLE EVIDENCE** | A later recovery scan for `b45a4e1e-...` recorded the no-resubmit outcome. |
| Release-signed update | **DEFERRED TO ISSUE #71** | Requires a real versionCode increase and release-to-release install/update chain. |
| One JSON per survey UUID | **PASS — DURABLE EVIDENCE** | 19 distinct UUIDs and no conflicting duplicate final JSON in the 2026-09-29–2026-10-01 dataset. |
| Remote artifact/path confirmation | **PASS — DURABLE EVIDENCE** | Remote paths, UUID correlation, and upload records are retained. |
| Voice artifact integrity | **FAIL** | 42 of 49 referenced WAVs retained; seven are missing after export/submission evidence. |
| Diagnostic artifact integrity | **PASS — PARTIAL EVIDENCE** | 19/19 final JSONs have matching logcats, without a complete artifact manifest. |
| `LOCKED_BOOT_COMPLETED` | **NOT EVIDENCED / not required** | Not required unless supported-device policy later says otherwise. |

## Remaining follow-up

- Issue #71 owns a real release-signed versionCode increase and
  release-to-release install/update verification, including
  `MY_PACKAGE_REPLACED` recovery continuity.
- Root-cause investigation and a verified retention/publication outcome for
  the seven missing optional voice WAV artifacts.
- An explicit diagnostic-artifact manifest if full diagnostic completeness is
  an acceptance requirement.

## Conclusion

**Issue #38 is completed / satisfied.** It has durable physical-device and
remote evidence for mandatory survey JSON preservation and recovery, including
offline pending, process restart, reconnect, reboot, canonical remote upload,
and post-success no-resubmit behavior. The optional voice artifact
retention/publication failure remains separate from mandatory survey JSON
acceptance. A genuine signed release-update / `MY_PACKAGE_REPLACED` scenario
is intentionally deferred to Issue #71, not treated as an outstanding Issue
#38 acceptance failure. This update changes documentation only; it makes no
claim of a source or configuration change and does not modify `TODO.md` or
`ROADMAP.md`.
