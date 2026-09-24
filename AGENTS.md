# AGENTS.md

## Project

This repository contains the **Survey2026 / SurveyApp** Android application.

Primary technologies:

- Kotlin
- Jetpack Compose
- Android WorkManager
- Android NDK / CMake
- whisper.cpp for on-device speech recognition
- LiteRT-LM for on-device language-model inference

The Android Studio project name is `SurveyNav`; the repository/product name is **Survey2026**.

Use the **current checked-out repository contents** as the source of truth.

Do not assume that code, fixes, dependencies, configuration, test behavior, or architectural decisions from another branch are present unless they are verified in the current branch.

For project status and priorities:

- `README.md` — current architecture, build, runtime, and operational documentation
- `ROADMAP.md` — completed/validated baseline and product direction
- `TODO.md` — concrete unfinished work

---

## General Development Rules

- Make the smallest change that correctly solves the requested problem.
- Prefer permanent/root-cause fixes over local workarounds.
- Avoid broad refactoring unless the task requires it.
- Do not change unrelated code while fixing a specific issue.
- Do not update dependencies unless explicitly requested or the task cannot be completed without it.
- Do not update whisper.cpp, LiteRT-LM, AGP, Kotlin, NDK, CMake, SDK levels, or ABI policy as a side effect of unrelated work.
- Preserve existing validated behavior unless the task explicitly requires changing it.
- Prefer evidence from current code, tests, logs, and real-device results over assumptions.
- Investigate before modifying lifecycle, concurrency, WorkManager, JNI, native integration, release signing, or recovery behavior.
- Do not hide failures with arbitrary sleeps, unconditional retries, swallowed exceptions, or fixed delays.
- Do not suppress errors or warnings without understanding their cause.
- Keep production behavior and developer diagnostics conceptually separate.

---

## Worktree and Git Safety

- Preserve unrelated local changes.
- Do not use `stash`, `reset`, `checkout`, `restore`, or `clean` to dispose of unrelated work unless explicitly requested.
- Do not stage unrelated files.
- Prefer explicit paths with `git add` over broad staging commands.
- Do not delete untracked files until their purpose is understood.
- Do not commit generated APKs, keystores, credentials, model artifacts, or local-only build outputs unless they are intentionally part of the requested deliverable.
- Do not commit, push, force-push, tag, open a pull request, merge, or otherwise modify remote Git state unless explicitly requested.

When a task includes a requested commit, keep the commit focused on that task.

---

## Survey Product Behavior

Treat survey configuration, routing, prompt behavior, scoring, follow-up generation, and finalization as user-visible product behavior.

Before modifying survey behavior:

- inspect the current YAML configuration
- inspect the parser/resolver code
- inspect the ViewModel/repository path that consumes it
- inspect relevant tests

Do not change wording, routing, score thresholds, prompt structure, follow-up capacity, node semantics, or language behavior as part of unrelated work.

Do not assume that:

- configured prompt structure
- runtime interaction flow
- persisted survey state
- transient AI/composer state

are the same concept.

Preserve existing tested behavior unless the task explicitly requests a behavior change.

---

## Current Survey Finalization / Upload Architecture

Survey JSON is the mandatory survey artifact.

The current upload/recovery architecture uses one shared reconciliation path.

Primary files:

- `app/src/main/kotlin/com/negi/survey/net/PendingSurveyUploads.kt`
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadFinalizer.kt`
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadRescheduler.kt`
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadWork.kt`
- `app/src/main/kotlin/com/negi/survey/net/SurveyUploadWorkTracker.kt`
- `app/src/main/kotlin/com/negi/survey/net/UploadRescheduleReceiver.kt`
- `app/src/main/kotlin/com/negi/survey/SurveyApp.kt`

Expected flow:

```text
Review / Finish
    |
    v
SurveyUploadFinalizer
    |
    v
SurveyUploadWork.reconcile(...)
    |
    v
WorkManager / tracker / uploaded-state reconciliation
```

Recovery flow:

```text
Startup / BOOT_COMPLETED / MY_PACKAGE_REPLACED / network recovery
    |
    v
SurveyUploadRescheduler.recoverPendingSurveyUploads(...)
    |
    v
PendingSurveyUploads discovery
    |
    v
SurveyUploadWork.reconcile(...)
```

Rules:

- Keep logical survey identity based on the survey, not only on a file path.
- Preserve duplicate logical-survey suppression.
- Preserve canonical pending-artifact selection.
- Preserve tracker and uploaded-state checks.
- Preserve the shared reconciliation path.
- Do not reintroduce a second direct survey-worker enqueue path.
- Do not restore the old `reenqueuePendingSurveyUploads()` design.
- Do not add upload behavior to the Done screen.
- `Start New Survey` is reset/navigation behavior, not an upload trigger.
- Generic diagnostic upload failures must not be treated as proof that mandatory survey JSON recovery failed.

When modifying recovery behavior, verify startup, restart, reboot, package replacement, reconnect, duplicate suppression, and post-success no-resubmit behavior as relevant to the change.

---

## WorkManager / Background Work

WorkManager behavior is stateful and persistence-sensitive.

When modifying WorkManager code:

- inspect unique-work naming
- inspect ExistingWorkPolicy usage
- inspect tracked WorkRequest IDs
- inspect terminal vs active work handling
- inspect retry semantics
- inspect network constraints
- inspect process/reboot persistence
- inspect startup reconciliation
- inspect receiver behavior

Do not assume an enqueue call means work was durably accepted without checking the surrounding code contract.

Do not create competing scheduling paths for the same logical survey.

Do not use broad work cancellation as a shortcut unless the requested behavior explicitly requires it.

---

## Kotlin / Coroutines

- Write source-code comments in English.
- Prefer KDoc for public or non-obvious APIs.
- Keep coroutine ownership and cancellation explicit.
- Avoid blocking the UI thread.
- Preserve structured concurrency where practical.
- Avoid `GlobalScope`.
- Do not assume coroutine cancellation can interrupt synchronous JNI/native work.
- Be careful when holding `Mutex` or other locks across native or blocking calls.
- Do not allow stale callbacks from old work to update newer survey/inference state.
- Rethrow `CancellationException` when a broad exception handler would otherwise swallow it.

---

## LiteRT-LM

Main integration:

`app/src/main/kotlin/com/negi/survey/slm/`

Important files include:

- `LiteRtLM.kt`
- `AiRepository.kt`
- related model/inference lifecycle code

Current dependency baseline:

`com.google.ai.edge.litertlm:litertlm-android:0.16.1`

When modifying LiteRT-LM integration:

- Treat Engine and Conversation lifecycle as concurrency-sensitive.
- Do not create concurrent sessions unless the architecture explicitly supports them.
- Do not assume `withTimeout` or coroutine cancellation interrupts a blocking JNI/native call.
- Handle late callbacks safely.
- Avoid duplicate cleanup.
- Prevent stale callbacks from one run from corrupting a newer run.
- Distinguish normal completion, cancellation, timeout, recovery, and stuck/poisoned session behavior when evidence supports the distinction.
- Do not add fixed delays to successful inference paths without evidence.
- Preserve serialization/gating guarantees around inference and Conversation recreation.
- Prefer retry/backoff only for demonstrated transient failures.

Before non-trivial lifecycle changes, inspect:

- Engine creation/destruction
- Conversation creation/destruction
- inference serialization
- Mutex/gate ownership
- callback handling
- cancellation
- watchdogs
- timeout handling
- recovery
- cleanup
- Conversation recreation
- run/survey ownership

---

## AI Follow-up / TWO_STEP Behavior

The shipped English and Swahili Q8-Q17 baseline uses TWO_STEP behavior where configured.

Preserve these distinctions:

1. EVAL evaluates the answer.
2. Evaluation output is structured data, not respondent-facing text.
3. FOLLOWUP generation is a separate step.
4. Only accepted, non-duplicate follow-up text is persisted.
5. Stale/cancelled inference must not update a replacement chain.

Evaluation parsing expects strict structured fields including:

- `score`
- `missing_points`
- `followup_needed`

Malformed or contradictory evaluation output fails closed.

Do not treat passing deterministic tests as proof of real-model semantic quality. Real-model language quality remains a separate acceptance activity.

---

## Whisper.cpp / Native Audio

`whisper.cpp` is managed as a Git submodule.

Do not move the submodule to a different commit, tag, or branch unless explicitly requested.

Native integration is primarily under:

`nativelib/`

Current CMake entry point:

`nativelib/src/main/jni/whisper/CMakeLists.txt`

Current native baseline:

- NDK `29.0.14206865`
- CMake `3.22.1`
- ABI: `arm64-v8a`
- CPU-only JNI build
- whisper.cpp baseline: v1.9.3

The native build currently forces non-CPU GGML backends off.

Changes to Whisper/GGML/CMake configuration must be narrowly scoped and validated with the Android native build.

Do not automatically absorb new upstream source files into the JNI target without verifying that they belong to the intended Whisper/GGML build.

---

## Git Submodules

After cloning or when submodules are missing:

```bash
git submodule sync --recursive
git submodule update --init --recursive
```

Do not modify a submodule pointer as a side effect of unrelated work.

---

## Build Baseline

Current toolchain:

- AGP 9.3.2
- Kotlin 2.4.10
- Compose BOM 2026.08.00
- Java 17
- compileSdk 37
- targetSdk 36
- minSdk 26
- NDK 29.0.14206865
- CMake 3.22.1

AGP 9 built-in Kotlin is used. Do not add `org.jetbrains.kotlin.android` unless the project design intentionally changes.

Safe JVM/debug validation without embedded development secrets:

```bash
./gradlew :app:testDebugUnitTest --no-daemon \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false

./gradlew :app:assembleDebug --no-daemon \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false
```

Release assembly:

```bash
./gradlew :app:assembleRelease --no-daemon \
  -PskipModelDownload=true \
  -Prelease.allowSecrets=false
```

Do not run `clean` unnecessarily during iterative work because Android/native rebuilds are expensive.

The build may initialize submodules or acquire model assets depending on build properties and local state. Do not delete downloaded model assets as unrelated cleanup.

---

## Release Signing

Signing, secrets, artifact identity, and publication are release-critical.

Expected local signing state after the release-signing cleanup:

```text
Variant: debug
Config: debug

Variant: release
Config: none
```

Rules:

- Do not force release builds to use the Android debug key.
- `release.useDebugSigning=true` is explicit opt-in behavior only.
- Do not hard-code production keystore paths or passwords into tracked files.
- Never print keystore passwords, API tokens, or embedded credentials.
- Production release signing is handled separately from normal local Gradle release assembly.
- Preserve signing-certificate continuity for install/update compatibility.

When changing release/signing behavior, verify `:app:signingReport` and the produced artifact as appropriate.

---

## Secret Handling

Do not commit or print:

- release keystores
- keystore passwords
- GitHub tokens
- Hugging Face tokens
- other credentials

Debug/release credential embedding is controlled by build properties.

The Hugging Face token transport implementation uses AES-GCM material to avoid storing the plaintext token directly in BuildConfig. Because decryption material is delivered with the application, treat this as APK obfuscation/plaintext avoidance, **not** as a secure secret-storage boundary against determined APK analysis.

Do not weaken plaintext-token checks in CI without a concrete reason.

---

## Dependency Verification

To inspect the resolved LiteRT-LM dependency:

```bash
./gradlew \
  :app:dependencies \
  --configuration debugRuntimeClasspath
```

Do not rely only on a declared version when debugging dependency behavior. Inspect the resolved graph when relevant.

---

## Native Build Warnings

Do not treat a CMake capability-test warning as a build failure if the final Gradle build succeeds.

Investigate warnings separately when they affect correctness, performance, or the requested task.

Do not change linker, LTO/IPO, compiler, ABI, NDK, CMake, or backend flags as part of an unrelated Kotlin or upload/recovery fix.

---

## Validation Strategy

Use the narrowest relevant validation first.

Distinguish:

- JVM unit tests
- Android instrumentation tests
- branch CI
- real-device functional tests
- real-model semantic tests
- release assembly/signing checks

After modifying application Kotlin code, normally:

1. Review `git diff`.
2. Ensure no unrelated files changed.
3. Run relevant narrow tests.
4. Run the appropriate build task.

For upload/recovery changes, use the existing JVM tests around:

- pending discovery
- finalization
- rescheduling
- work reconciliation
- work tracking
- startup/receiver recovery

For native changes, confirm the relevant CMake/native targets rebuild successfully.

For real-device recovery changes, validate only the scenarios affected by the change, but preserve awareness of:

- offline pending
- reconnect
- restart
- reboot
- app replacement
- duplicate suppression
- post-success no-resubmit

Do not rerun expensive or stochastic real-model tests for unrelated documentation-only changes.

---

## CI / Release Workflows

Workflows:

- `.github/workflows/BranchBuild.yml`
  - JVM tests
  - debug APK
  - plaintext HF-token marker check
  - branch artifact
  - branch preview publication

- `.github/workflows/AndroidBuild.yml`
  - main/release build path
  - release signing
  - release metadata
  - GitHub Release / download-page publication behavior

Rules:

- Do not trigger a production/publish workflow unless explicitly requested.
- Treat signing, artifact naming, hashes, stable checkpoint identity, `latest.json`, and download-page generation as release-critical.
- When changing release packaging, verify published metadata corresponds to the exact source/artifact being released.

---

## Documentation

Keep documentation responsibilities distinct:

- `README.md` — current verified architecture, build, runtime, CI, and operational behavior
- `ROADMAP.md` — completed/validated baseline, known limitations, and product direction
- `TODO.md` — concrete unfinished implementation, verification, and documentation work

Rules:

- Do not document proposed behavior as implemented.
- Do not leave completed baseline work as perpetual unchecked TODOs.
- Mark work complete only when its stated implementation/validation requirements are actually satisfied.
- Keep branch/commit-specific status accurate when updating docs.
- Documentation-only changes normally do not require an Android build unless they alter generated/executable behavior.

For Markdown-only changes:

```bash
git diff --check
git diff -- <changed-file>
git status --short --untracked-files=all
```

---

## Before Finishing a Task

Report:

- files changed
- reason for each change
- important behavioral changes
- tests/build commands run
- whether they succeeded
- anything intentionally not tested
- remaining risks or unverified assumptions

Do not claim a real-device, CI, release, or model-quality result unless there is evidence for it.

Do not create commits, push branches, open pull requests, merge, tag, or otherwise modify remote Git state unless explicitly requested.
