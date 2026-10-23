# Survey2026 Developer Environment Setup

This guide describes a reproducible local setup for Survey2026. It complements
[AGENTS.md](../AGENTS.md), which is the authoritative source for repository
development rules. Follow the instructions in that file when they differ from
this guide.

## Prerequisites

- Android Studio with the Android SDK components required by the checked-out
  project
- JDK 17
- Git, including submodule support
- A local checkout directory of your choice

This guide does not make a particular user directory, desktop application, or
account entitlement a repository requirement.

The current Android, Kotlin, Compose, and LiteRT-LM versions are declared in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml). The Android SDK
levels are declared in [`app/build.gradle.kts`](../app/build.gradle.kts).
Consult those files and `AGENTS.md` rather than treating a copied version list
as an independent source of truth. `AGENTS.md` also records the current native
toolchain baseline.

## Clone and initialize the repository

Choose a checkout location appropriate for your machine, then clone and
initialize the tracked submodules:

```bash
git clone https://github.com/ishizuki-tech/Survey2026.git Survey2026
cd Survey2026
git submodule sync --recursive
git submodule update --init --recursive
```

Do not change the `whisper.cpp` submodule revision as part of ordinary setup or
unrelated work.

## Use a task worktree

Git worktrees are a recommended way to isolate concurrent tasks and preserve
unrelated local changes. The checkout location and branch name are your local
choices; Survey2026 does not prescribe a directory layout or an agent-specific
branch prefix.

From a suitable existing checkout, create a task worktree only after verifying
the intended starting point and that the destination is unused:

```bash
git worktree add ../Survey2026-task -b task/example origin/main
cd ../Survey2026-task
git status --short --branch
```

Choose the example directory, branch, and starting revision appropriately for
your task, and verify each before executing the command. For an existing remote
branch, fetch or otherwise verify its intended revision before creating the
worktree. Do not switch branches, delete worktrees, or use destructive Git
commands to make room for a task. Preserve existing worktrees and their
uncommitted changes.

## Repository rules

Read [AGENTS.md](../AGENTS.md) before making changes. In particular:

- Make the smallest correct change; do not refactor or update dependencies as a
  side effect of an unrelated task.
- Preserve unrelated local changes. Do not use `stash`, `reset`, `checkout`,
  `restore`, or `clean` to dispose of them.
- Stage explicit paths only. Do not commit, push, merge, tag, create pull
  requests, or otherwise change remote Git state without explicit authorization.
- Do not commit or print credentials, keystores, tokens, model artifacts, or
  local build outputs.
- Treat device access, production packages, model inference, and release work
  as separately authorized, safety-sensitive activities.

For ordinary local JVM/debug validation without embedded development secrets,
the current documented command is:

```bash
./gradlew :app:testDebugUnitTest --no-daemon \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false
```

Use the narrowest relevant validation and follow the additional build, device,
release-signing, and native-build requirements in `AGENTS.md`. Do not infer
that a local machine has an authorized device, credentials, model assets, or
permission to download them.

## Claude Code, Claude Chat, Codex, and GitHub

These tools can support different parts of a development workflow:

| Surface | Typical role |
|---|---|
| Claude Chat | Investigation, planning, requirements discussion, review, and task preparation |
| Claude Code | Local source changes, builds, tests, and other permitted development operations |
| Codex | Another supported local implementation and testing workflow |
| GitHub | Authoritative remote source for repository, pull request, issue, and CI state |

This division describes a project workflow, not a universal product-capability
claim. Whether a particular Claude Chat or Claude Code session can read files,
run commands, edit sources, or access GitHub depends on its connected tools,
permissions, and selected environment.

## Claude-specific local setup

This repository currently does **not** track `CLAUDE.md`,
`.claude/settings.json`, `.claude/hooks/bash_guard.py`, or
`.claude/hooks/test_bash_guard.py`. Consequently, no Claude permission model,
hook, global settings file, desktop navigation behavior, session-persistence
behavior, or automatic instruction-loading behavior is repository-enforced by
those paths.

You may use personal Claude Code or Claude Chat settings when they are available
to you, but treat them as local preferences. Do not publish personal paths,
settings, credentials, or permissions as project requirements, and do not use
local configuration to bypass the safety rules in `AGENTS.md`.

Creating a personal Claude project is optional and depends on your account,
available product features, and access permissions. Likewise, connecting a
repository URL does not itself grant GitHub access or establish a scheduled
status check. Any GitHub integration requires separate authorization and should
be verified from the environment in use.

## Suggested workflow

1. Use GitHub or an authorized integration to verify the relevant branch, pull
   request, issue, and CI state.
2. Use Claude Chat, Claude Code, Codex, or another approved tool to investigate
   and prepare a scoped task.
3. Work in an isolated checkout when appropriate, verify the branch and working
   tree, and apply only the requested changes.
4. Run the relevant validation, inspect the diff, and report what was changed
   and what was not verified.

The chosen tool does not replace repository policy: `AGENTS.md`, the current
checked-out source, and explicitly granted authorization govern the work.
