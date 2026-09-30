---
name: Engineering Issue
about: Track engineering investigation, implementation, and validation
title: "[Engineering] "
labels: "engineering"
assignees: ""
---

## Source bug report

<!--
Link the original Bug Report issue if one exists.

Example:
Source bug report: #64

If this issue did not originate from a Bug Report, explain the source briefly.
-->

Source bug report:

## Summary

<!--
Summarize the engineering problem in technical terms.

This section may refine or reinterpret the original bug report based on
investigation, but should remain factual and evidence-based.
-->

## Observed behavior

<!--
Describe the confirmed behavior relevant to the engineering investigation.

Reference the source Bug Report rather than duplicating all screenshots,
logs, and reporter notes unless necessary.
-->

## Expected behavior

<!-- Describe the intended system behavior. -->

## Evidence / current implementation

<!--
Document confirmed evidence from the current implementation, logs, traces,
tests, uploaded artifacts, or Git history.

Clearly distinguish confirmed facts from hypotheses.
-->

## Execution / data flow

<!--
Trace the relevant execution or data flow when useful.

Example:

UI
-> ViewModel
-> finalization snapshot
-> staged JSON
-> upload worker
-> remote artifact
-->

## Root cause

<!--
State the root cause only when it has been established by evidence.

If the root cause is not yet established, write:

Root cause not yet established.
-->

## Remaining hypotheses

<!--
List only hypotheses that still require verification.

If none remain, write:

None currently identified.
-->

## Minimal fix

<!--
Describe the smallest safe change that addresses the established problem.

Avoid unrelated refactoring or cleanup.
-->

## Files / areas affected

<!--
List the source files, modules, configs, or subsystems expected to be involved.
-->

## Tests required

<!--
List the tests and validation needed to demonstrate the fix and prevent
regression.
-->

- [ ] Relevant unit tests
- [ ] Relevant UI / instrumentation tests, if applicable
- [ ] Existing regression tests
- [ ] `git diff --check`
- [ ] `:app:testDebugUnitTest`, if applicable
- [ ] `:app:assembleDebug`, if applicable
- [ ] Real-device validation, if required

## Regression risks

<!--
Identify behavior that could be affected by the change and must remain stable.
-->

## Non-goals

<!--
Explicitly list adjacent behavior that must not be changed by this work.
-->

## Remaining unknowns

<!--
Record unresolved questions or missing evidence.

If none remain, write:

None currently identified.
-->

## Implementation tracking

<!--
Link the implementation PR once one exists.

Example:
Implementation PR: #66
-->

Implementation PR:

`Not created yet`
