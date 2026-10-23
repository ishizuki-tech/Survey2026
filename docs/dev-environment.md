# Survey2026 Developer Environment Setup

This guide describes how to replicate the Claude Code + Claude Chat development
workflow used in this project.

---

## Prerequisites

- **macOS** (development and validation use macOS; confirmed on Darwin 25.x)
- **Android Studio** with SDK Manager — see [Toolchain versions](#toolchain-versions) below
- **JDK 17** (`/usr/libexec/java_home -v 17` must resolve)
- **Claude Code Desktop App** — download from <https://claude.ai/download>
- **Claude account** with access to the Survey2026 Claude Chat project

---

## Repository setup

The repository uses Git worktrees so that multiple tasks (Claude Code sessions,
Codex workflows) can be in flight simultaneously without interfering with each other.

### Main checkout

```
/Users/negi/Gates2026/Surveys0828   [codex-issue-85-production-diagnostic-redaction]
```

This is the canonical repository root. Claude Code sessions **must not** edit files
here directly; they work in a dedicated worktree (see below).

### Claude Code worktrees

Claude Code sessions work only inside `~/Gates2026/claude/<name>` on a matching
`claude/<name>` branch. The initial setup worktree is `setup-claude-code`:

```
~/Gates2026/claude/setup-claude-code    [claude/setup-claude-code]
~/Gates2026/claude/issue-103-s25-recovery-test    [claude/103-s25-recovery-test]
```

To create a new worktree for a task, run from the main checkout:

```bash
git worktree add ~/Gates2026/claude/<issue>-<slug> -b claude/<issue>-<slug>
```

Open that folder in the Claude Code Desktop App as the session's working directory.

### Codex worktrees

`/Users/negi/.codex/worktrees/**` and `~/Gates2026/Survey2026-*` are managed by
Codex. Claude Code must never edit files in those locations.

---

## Claude Code configuration

### Project-local settings file

Location: `<worktree>/.claude/settings.json`

This file controls the permission model and the `bash_guard.py` pre-tool hook for
all Claude Code sessions opened in this worktree. The hook fails closed: if it
cannot parse a command, the command is blocked.

**Do not edit, disable, or work around this file.** Propose changes to NEGI instead.

After any approved change, run:

```bash
python3 -I .claude/hooks/test_bash_guard.py
```

#### Permissions summary

The project settings split commands into three tiers:

| Tier | Behavior | Examples |
|---|---|---|
| `allow` | Runs without prompting | `git status`, `git diff`, `git log`, safe `adb` reads, approved Gradle test/assemble tasks |
| `ask` | Prompts for approval | `git add`, `git commit`, `git fetch`, `git worktree add`, device install commands, GitHub CLI |
| `deny` | Always blocked | `git push`, `git stash`, `git reset --hard`, `git clean`, Gradle `install`/`clean`, `adb uninstall`, `adb logcat --clear`, `gh pr create`, `gh workflow run`, protected file reads/edits |

#### Hooks

A single `PreToolUse` hook applies to all shell and file tools:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash|Monitor|PowerShell|mcp__terminal__run_in_terminal|Read|Edit|Write|NotebookEdit|Grep|Glob",
        "hooks": [
          {
            "type": "command",
            "command": "/usr/bin/env python3 -I \"$CLAUDE_PROJECT_DIR/.claude/hooks/bash_guard.py\"",
            "timeout": 30,
            "onFailure": "block",
            "statusMessage": "Survey2026 safety guard"
          }
        ]
      }
    ]
  }
}
```

#### `disableBypassPermissionsMode`

Not explicitly set in any settings file — not configured as of this writing.

### Global settings file

Location: `~/.claude/settings.json`

Contains only:

```json
{ "agentPushNotifEnabled": true }
```

Push notifications for agent completions are enabled globally.

---

## Claude Code Desktop App usage

1. Open the Claude Code Desktop App.
2. Use **File → Open Folder** (or the folder picker in the sidebar) to select the
   worktree directory: `~/Gates2026/claude/<name>`.
3. The session will load project instructions from `CLAUDE.md` and `AGENTS.md`
   automatically.
4. Sessions opened this way are tied to the worktree folder and persist across
   restarts until explicitly archived.

**Branch rule**: always verify you are on the correct `claude/<name>` branch before
doing any work:

```bash
git branch --show-current
```

If you are on a different branch (e.g. after a `git checkout` for a push), return
to the session branch before continuing:

```bash
git checkout claude/<name>
```

---

## Survey2026 — Chat integration

> **Note**: This section describes the intended workflow as understood from project
> context. No dedicated documentation file was found for this workflow at the time
> of writing; details may be incomplete.

The project uses two separate Claude surfaces:

| Surface | Role |
|---|---|
| **Claude Code** (Desktop App, Code tab) | Implementation: file edits, Gradle builds, adb commands, git operations |
| **Claude Chat** (claude.ai, Survey2026 project) | Planning: morning check, task specification authoring, PR management, cross-session coordination |

### Typical flow

1. **Morning check** — open the Survey2026 Chat project on claude.ai; review open
   issues, CI status, and prioritize the day's tasks.
2. **Task specification** — draft a task spec in Chat (markdown, with explicit
   steps, non-goals, and branch/commit instructions).
3. **Execution** — paste the task spec into a Claude Code session; Claude Code
   implements the changes, runs tests, and reports back.
4. **Review** — paste the Claude Code output (diff summary, test results) back into
   Chat for cross-session review or PR description drafting.
5. **PR management** — Chat assists with PR descriptions, labels, and merge
   decisions; Claude Code handles the local git operations.

---

## Development rules (from AGENTS.md)

Key rules every developer must follow:

### General
- Make the **smallest change** that correctly solves the problem.
- Prefer root-cause fixes over workarounds.
- Do not change unrelated code while fixing a specific issue.
- Do not update dependencies (LiteRT-LM, whisper.cpp, AGP, Kotlin, NDK, CMake) as
  a side effect of unrelated work.

### Git / worktrees
- Never use `stash`, `reset`, `checkout`, `restore`, or `clean` to dispose of
  unrelated work.
- Prefer `git add <explicit path>` over `git add -A` or `git add .`.
- Do not commit APKs, keystores, credentials, model artifacts, or local build outputs.
- **Do not commit, push, open PRs, or merge without explicit instruction from NEGI.**

### Builds
- Always use JDK 17.
- Always pass: `--no-daemon -PskipModelDownload=true -Pdebug.embedSecrets=false -Prelease.allowSecrets=false`
- Prefer `--offline`; going online or downloading models requires approval.
- `-PlocalBuild=true` is **required** for any APK that will be installed on a device.
- Never run `./gradlew installDebug`, `uninstallAll`, or `clean`.

### Device safety (Galaxy S25 FE, SM-S731U, serial R5GL2118ZGD)
- Do not use the device without NEGI's explicit approval for the current task.
- Use only `com.negi.survey.local`. Never install, clear, or force-stop
  `com.negi.survey` (production package).
- Before every `adb install -r`: verify package name with `aapt2 dump badging`,
  record APK SHA-256, confirm device serial, compare versionCode/signing.
- Never run `adb logcat -c` / `--clear`.
- Model inference and GPU benchmarks require explicit approval per run.

### Upload / recovery architecture
- Preserve the single shared reconciliation path (`SurveyUploadWork.reconcile()`).
- Do not reintroduce a second direct `reenqueuePendingSurveyUploads()` enqueue path.
- Survey JSON is the mandatory artifact; generic diagnostic upload failures are
  not evidence of survey recovery failure.

### Secrets
- Never commit release keystores, keystore passwords, GitHub tokens, or
  Hugging Face tokens.

### Reporting
- Report to NEGI in **Japanese**.
- Keep source code comments in **English**.

---

## Toolchain versions

| Component | Version |
|---|---|
| AGP | 9.3.2 |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.08.00 |
| Java | 17 |
| compileSdk | 37 |
| targetSdk | 36 |
| minSdk | 26 |
| NDK | 29.0.14206865 |
| CMake | 3.22.1 |
| LiteRT-LM | 0.18.0 |
| whisper.cpp | v1.9.3 |
| Native Android ABI | arm64-v8a |

---

## Claude Chat Setup (per engineer)

Each developer uses their own claude.ai account. Do NOT share the project owner's
Claude chat project. Instead:

1. Sign in to https://claude.ai with your own account.
2. Create a new project named "Survey2026 — Chat" (or any name you prefer).
3. In the project settings, add the repository:
   https://github.com/ishizuki-tech/Survey2026
4. Add project instructions: paste the contents of AGENTS.md as the project instructions,
   or write: "Read AGENTS.md at the start of every session."
5. Each morning, send "朝チェック" (morning check) to get the current GitHub state
   (main HEAD, open PRs, open P1 issues).
6. Receive implementation prompts from the chat, run them in Claude Code Local,
   and paste results back for review.

---

## Sections not filled from available files

- **Claude Code Desktop App usage** — the folder-open workflow is inferred from
  general Claude Code behavior; no project-specific documentation was found.
- **Survey2026 — Chat integration** — workflow described from context inference;
  no dedicated doc file exists in the repository as of this writing.
