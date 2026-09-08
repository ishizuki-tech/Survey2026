# AGENTS.md

## Project

This repository contains the SurveyApp Android application.

Primary technologies include:

* Kotlin
* Jetpack Compose
* Android NDK / CMake
* whisper.cpp for on-device speech recognition
* LiteRT-LM for on-device language model inference

Use the current repository contents as the source of truth.

Do not assume that code, fixes, dependencies, or architectural decisions from other branches are already present.

## General Development Rules

* Make the smallest change that correctly solves the requested problem.
* Avoid broad refactoring unless explicitly requested.
* Do not change unrelated code while fixing a specific issue.
* Do not update dependencies unless explicitly requested.
* Do not update Whisper.cpp or LiteRT-LM unless explicitly requested.
* Preserve existing behavior unless the task explicitly requires changing it.
* Prefer evidence from the current code over assumptions.
* Investigate before modifying complex lifecycle, concurrency, JNI, or native integration code.
* Do not hide failures with arbitrary sleeps or fixed delays.
* Do not suppress errors or warnings without understanding their cause.

## Worktree Safety

* Preserve unrelated local changes.
* Do not use stash, reset, checkout, restore, or clean to handle unrelated work unless explicitly requested.
* Do not stage unrelated files; prefer explicit file paths over broad staging commands.
* Do not commit, push, force-push, tag, or open a pull request unless explicitly requested.

## Survey and Configuration Changes

* Treat survey configuration and prompt behavior as user-visible product behavior.
* Verify config semantics against the current config files, parser/resolver code, and relevant tests before changing them.
* Do not change wording, routing, scoring, prompt structure, or node semantics as part of unrelated work.
* Do not assume a configured prompt pipeline and the runtime interaction flow are the same concept.
* Preserve existing tested behavior unless the task explicitly requests a behavior change.

## Kotlin

* Write code comments in English.
* Keep coroutine ownership and cancellation explicit.
* Avoid blocking the UI thread.
* Do not assume coroutine cancellation can interrupt synchronous JNI/native calls.
* Be careful when holding Mutex or other locks across native calls.
* Preserve structured concurrency where practical.
* Avoid GlobalScope.

## LiteRT-LM

The main LiteRT-LM integration is under:

`app/src/main/kotlin/com/negi/survey/slm/`

Important files include:

* `LiteRtLM.kt`
* `AiRepository.kt`
* related SLM/inference lifecycle code

When modifying LiteRT-LM integration:

* Treat Engine and Conversation lifecycle as concurrency-sensitive.
* Do not create multiple LiteRT-LM sessions concurrently unless the existing architecture explicitly supports it.
* Do not assume `withTimeout` or coroutine cancellation can interrupt a blocking JNI call.
* Handle late native callbacks safely.
* Avoid duplicate cleanup.
* Do not allow stale callbacks from an old inference to corrupt a newer inference.
* Distinguish normal completion, cancellation, recovery, and poisoned/stuck-session behavior when possible.
* Do not add fixed delays to the successful inference path unless there is clear evidence they are required.
* Preserve serialization/gating guarantees around inference and Conversation recreation.
* Prefer retry/backoff only when an actual transient error occurs rather than delaying every successful request.

Before making non-trivial lifecycle changes, inspect:

* Engine creation and destruction
* Conversation creation and destruction
* inference serialization
* Mutex/gate ownership
* callback handling
* cancellation
* watchdogs
* timeout handling
* recovery
* cleanup
* Conversation recreation

When modifying AI or conversation state:

* Keep conversation and session ownership explicit.
* Prevent stale callbacks or state from one run or session from affecting another.
* Preserve the distinction between persisted survey state and transient composer or inference state.
* Do not infer terminal UI state while asynchronous/native work or required interaction remains unresolved.
* Investigate state restoration and re-entry behavior before modifying it.

## Whisper.cpp

`whisper.cpp` is managed as a Git submodule.

Do not move the submodule to another commit, tag, or branch unless explicitly requested.

Native Whisper integration is primarily under:

`nativelib/`

Changes to Whisper/GGML CMake configuration should be kept narrowly scoped and tested with the Android native build.

Do not automatically absorb newly added upstream source files into the JNI target without checking whether they belong to Whisper itself.

## Git Submodules

After cloning or when submodules are missing, use:

```bash
git submodule sync --recursive
git submodule update --init --recursive
```

Do not modify the submodule pointer as a side effect of unrelated work.

## Build

Primary debug build:

```bash
./gradlew :app:assembleDebug
```

A full clean build may be used when necessary:

```bash
./gradlew clean
./gradlew :app:assembleDebug
```

Do not run `clean` unnecessarily during iterative development because native and Android rebuilds can be expensive.

The build may initialize Git submodules and download required local models when they are missing.

Do not delete downloaded model assets as part of unrelated cleanup.

## Dependency Verification

To inspect the resolved LiteRT-LM Android dependency:

```bash
./gradlew \
  :app:dependencies \
  --configuration debugRuntimeClasspath
```

Do not rely only on the declared version when investigating dependency behavior; check the resolved dependency graph when relevant.

## Native Build Warnings

Do not treat a CMake capability-test warning as a build failure if the final Gradle build succeeds.

Investigate native warnings separately when they affect correctness, performance, or the requested task.

Do not change linker, LTO/IPO, compiler, ABI, NDK, or CMake configuration as part of an unrelated Kotlin/LiteRT-LM fix.

## Validation

After modifying application or LiteRT-LM Kotlin code, at minimum:

1. Review `git diff`.
2. Ensure no unrelated files changed.
3. Run the relevant tests if they exist.
4. Run:

```bash
./gradlew :app:assembleDebug
```

For native changes, also confirm the relevant native/CMake targets are rebuilt successfully.

Run the narrowest relevant tests first and distinguish JVM unit tests, Android instrumentation tests, and real-model device tests. Do not rerun expensive or stochastic real-model tests for unrelated documentation-only or trivial assertion-only changes.

## CI and Release Changes

* Treat signing, secrets, release publishing, artifact naming, and download-page generation as release-critical behavior.
* Do not change release, signing, or publishing behavior as part of unrelated work.
* Never print or expose credentials or secrets.
* Do not trigger a production or publish workflow unless explicitly requested.
* When changing release packaging, verify that published metadata and artifacts correspond to the exact build being released.

## Documentation

* Keep `README.md` focused on current, verified behavior.
* Keep unfinished implementation and documentation work in `TODO.md`.
* Do not document proposed behavior as implemented.
* Mark a TODO complete only when all of its stated requirements are satisfied.
* Documentation-only changes normally do not require an Android build unless they affect generated or executable behavior.

## Before Finishing a Task

Report:

* files changed
* reason for each change
* important behavioral changes
* tests/build commands run
* whether they succeeded
* anything intentionally not tested
* remaining risks or unverified assumptions

Do not create commits, push branches, open pull requests, or modify remote Git state unless explicitly requested.

For Markdown-only changes, run `git diff --check`, review the changed file diff, and run `git status --short --untracked-files=all`.
