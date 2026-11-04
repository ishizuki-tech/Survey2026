#!/usr/bin/env bash
# Non-orchestrated, state-preserving controller for the isolated Issue #86 harness.
# It observes the target's self-termination; it never sends a process-termination command.
set -euo pipefail
umask 077
serial="R5GL2118ZGD"
target="com.negi.survey.issue86.it"
testpkg="${target}.test"
adb_bin="adb"
session="${1:-}"
scenario="${2:-}"
execute="${3:-}"
checkpoint_wait_seconds=120
exit_wait_seconds=30

valid_session_id() {
  [[ "$1" =~ ^issue86-pd-[A-Za-z0-9_-]{8,80}$ ]]
}

# Host evidence remains outside the app-private session directory, so reserve it
# independently. Tests override this with a private temporary directory.
configure_host_session_paths() {
  local session_id="$1"
  valid_session_id "$session_id" || return 1
  host_evidence_root=/private/tmp
  local lock_root=/private/tmp
  if [[ "${ISSUE86_RUNNER_LIB_ONLY:-false}" == true && "${BASH_SOURCE[0]}" != "$0" ]]; then
    host_evidence_root="${ISSUE86_HOST_EVIDENCE_DIR:-/private/tmp}"
    lock_root="${ISSUE86_TEST_LOCK_ROOT:-/private/tmp}"
  fi
  [[ "$host_evidence_root" == /* && -d "$host_evidence_root" && ! -L "$host_evidence_root" ]] || return 1
  [[ "$(cd -P -- "$host_evidence_root" && pwd -P)" == "$host_evidence_root" ]] || return 1
  [[ "$lock_root" == /* && -d "$lock_root" && ! -L "$lock_root" ]] || return 1
  [[ "$(cd -P -- "$lock_root" && pwd -P)" == "$lock_root" ]] || return 1
  host_evidence_dir="$host_evidence_root/${session_id}.issue86-evidence"
  phase1_log="$host_evidence_dir/phase1.log"
  phase2_log="$host_evidence_dir/phase2.log"
  legacy_phase1_log="$host_evidence_root/${session_id}.phase1.log"
  legacy_phase2_log="$host_evidence_root/${session_id}.phase2.log"
  session_lock_dir="$lock_root/.${session_id}.issue86-process-death.lock"
  session_lock_owner="$session_lock_dir/owner-pid"
}

# Check paths with lstat so symlinks are never accepted as evidence objects.
is_owned_private_directory() {
  perl -MFcntl=:mode -e '
    my @st = lstat(shift);
    exit !(@st && S_ISDIR($st[2]) && $st[4] == $> && ($st[2] & 07777) == 0700);
  ' "$1"
}

is_owned_private_file() {
  perl -MFcntl=:mode -e '
    my @st = lstat(shift);
    exit !(@st && S_ISREG($st[2]) && $st[4] == $> && $st[3] == 1 && ($st[2] & 07777) == 0600);
  ' "$1"
}

# mktemp is exclusive and creates mode-0600 files; lstat enforces the contract.
create_private_temp_file() {
  local template="$1" path
  [[ "$template" == /private/tmp/issue86-* ]] || return 1
  [[ -d /private/tmp && ! -L /private/tmp && "$(cd -P /private/tmp && pwd -P)" == /private/tmp ]] || return 1
  umask 077
  path="$(mktemp "$template")" || return 1
  is_owned_private_file "$path" || return 1
  printf '%s\n' "$path"
}

# Returns HOST_EVIDENCE_UNUSED, HOST_EVIDENCE_EXISTS, or HOST_EVIDENCE_ERROR.
classify_host_evidence() {
  local path existing=false
  [[ -n "${host_evidence_root:-}" && -n "${host_evidence_dir:-}" && -n "${phase1_log:-}" && -n "${phase2_log:-}" ]] || {
    printf '%s\n' HOST_EVIDENCE_ERROR
    return
  }
  [[ -d "$host_evidence_root" && ! -L "$host_evidence_root" && -r "$host_evidence_root" && -w "$host_evidence_root" && -x "$host_evidence_root" ]] || {
    printf '%s\n' HOST_EVIDENCE_ERROR
    return
  }
  for path in "${legacy_phase1_log:-}" "${legacy_phase2_log:-}"; do
    if [[ -n "$path" && ( -e "$path" || -L "$path" ) ]]; then
      if ! is_owned_private_file "$path"; then
        printf '%s\n' HOST_EVIDENCE_ERROR
        return
      fi
      existing=true
    fi
  done
  if [[ -L "$host_evidence_dir" ]]; then
    printf '%s\n' HOST_EVIDENCE_ERROR
    return
  fi
  if [[ -e "$host_evidence_dir" ]]; then
    if ! is_owned_private_directory "$host_evidence_dir"; then
      printf '%s\n' HOST_EVIDENCE_ERROR
      return
    fi
    for path in "$phase1_log" "$phase2_log"; do
      if [[ -e "$path" || -L "$path" ]]; then
        if ! is_owned_private_file "$path"; then
          printf '%s\n' HOST_EVIDENCE_ERROR
          return
        fi
      fi
    done
    existing=true
  fi
  if [[ "$existing" == true ]]; then
    printf '%s\n' HOST_EVIDENCE_EXISTS
  else
    printf '%s\n' HOST_EVIDENCE_UNUSED
  fi
}

# Atomically create both evidence files. A partially completed reservation is
# deliberately retained and blocks reuse rather than risking evidence loss.
reserve_host_evidence() {
  [[ "$(classify_host_evidence)" == HOST_EVIDENCE_UNUSED ]] || return 1
  umask 077
  mkdir -m 700 -- "$host_evidence_dir" || return 1
  is_owned_private_directory "$host_evidence_dir" || return 1
  set -o noclobber
  if ! exec 3> "$phase1_log"; then set +o noclobber; return 1; fi
  is_owned_private_file "$phase1_log" || { set +o noclobber; return 1; }
  if ! exec 4> "$phase2_log"; then set +o noclobber; return 1; fi
  is_owned_private_file "$phase2_log" || { set +o noclobber; return 1; }
  set +o noclobber
  # Reading is deferred until the corresponding instrumentation has exited.
}

# Open without following a final symlink or waiting on a FIFO. Validate the
# opened inode against the still-open writer before consuming any bytes.
read_reserved_evidence() {
  local path writer
  case "$1" in
    1) path="$phase1_log"; writer=3 ;;
    2) path="$phase2_log"; writer=4 ;;
    *) return 1 ;;
  esac
  perl -MFcntl=:DEFAULT,:mode -e '
    use strict; use warnings;
    use Encode qw(decode FB_CROAK LEAVE_SRC);
    my $path = shift;
    my @writer = stat(STDIN);
    my @before = lstat($path);
    exit 1 unless @writer && S_ISREG($writer[2]) && $writer[4] == $> && $writer[3] == 1 && ($writer[2] & 07777) == 0600;
    exit 1 unless @before && S_ISREG($before[2]) && $before[4] == $> && $before[3] == 1 && ($before[2] & 07777) == 0600 && $writer[0] == $before[0] && $writer[1] == $before[1];
    sysopen(my $reader, $path, O_RDONLY | O_NONBLOCK | O_NOFOLLOW) or exit 1;
    my @opened = stat($reader);
    exit 1 unless @opened && S_ISREG($opened[2]) && $opened[4] == $> && $opened[3] == 1 && ($opened[2] & 07777) == 0600 && $writer[0] == $opened[0] && $writer[1] == $opened[1];
    binmode $reader; binmode STDOUT;
    my $data = "";
    while (1) {
      my $n = sysread($reader, my $chunk, 65536);
      exit 1 unless defined $n;
      last if $n == 0;
      $data .= $chunk;
      exit 1 if length($data) > 16 * 1024 * 1024 || $chunk =~ /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/;
    }
    eval { decode("UTF-8", $data, FB_CROAK | LEAVE_SRC); 1 } or exit 1;
    my @after = lstat($path);
    exit 1 unless @after && S_ISREG($after[2]) && $after[4] == $> && $after[3] == 1 && ($after[2] & 07777) == 0600 && $writer[0] == $after[0] && $writer[1] == $after[1];
    print $data or exit 1;
  ' "$path" <&"$writer"
}

# A lock directory is atomic on the supported host filesystem. Stale locks are
# deliberately not removed: their presence keeps the session fail-closed.
classify_session_lock() {
  local owner
  [[ -n "${session_lock_dir:-}" && -n "${session_lock_owner:-}" ]] || {
    printf '%s\n' LOCK_ERROR
    return
  }
  if [[ ! -e "$session_lock_dir" && ! -L "$session_lock_dir" ]]; then
    printf '%s\n' LOCK_ABSENT
    return
  fi
  [[ -d "$session_lock_dir" && ! -L "$session_lock_dir" ]] && is_owned_private_directory "$session_lock_dir" || {
    printf '%s\n' LOCK_ERROR
    return
  }
  [[ -f "$session_lock_owner" && ! -L "$session_lock_owner" ]] && is_owned_private_file "$session_lock_owner" || {
    printf '%s\n' LOCK_ERROR
    return
  }
  owner="$(cat "$session_lock_owner")" || { printf '%s\n' LOCK_ERROR; return; }
  [[ "$owner" =~ ^[0-9]+$ ]] || { printf '%s\n' LOCK_ERROR; return; }
  if kill -0 "$owner" 2>/dev/null; then
    printf '%s\n' LOCK_ACTIVE
  else
    printf '%s\n' LOCK_STALE
  fi
}

session_lock_owned=false
# Once instrumentation is launched, retain the reservation unless the full
# expected protocol completes. Host-process exit alone does not prove that the
# Android-side operation is quiescent.
session_operation_uncertain=false
acquire_session_lock() {
  [[ "$(classify_session_lock)" == LOCK_ABSENT ]] || return 1
  (umask 077; mkdir -m 700 "$session_lock_dir") || return 1
  is_owned_private_directory "$session_lock_dir" || return 1
  if ! ( umask 077; set -o noclobber; printf '%s\n' "$$" > "$session_lock_owner" ); then
    # The directory was created by this process, but an incomplete reservation
    # is safer to preserve than to remove automatically.
    return 1
  fi
  is_owned_private_file "$session_lock_owner" || return 1
  session_lock_owned=true
}

release_session_lock() {
  [[ "$session_lock_owned" == true ]] || return 0
  [[ "$session_operation_uncertain" == false ]] || return 0
  [[ -d "$session_lock_dir" && ! -L "$session_lock_dir" ]] && is_owned_private_directory "$session_lock_dir" || return 1
  [[ -f "$session_lock_owner" && ! -L "$session_lock_owner" ]] && is_owned_private_file "$session_lock_owner" || return 1
  [[ "$(cat "$session_lock_owner")" == "$$" ]] || return 1
  rm -- "$session_lock_owner" || return 1
  rmdir -- "$session_lock_dir" || return 1
  session_lock_owned=false
}

cleanup_session_lock() {
  local status=$?
  trap - EXIT
  if ! release_session_lock; then
    echo "could not release session execution lock" >&2
    [[ "$status" != 0 ]] || status=1
  fi
  exit "$status"
}

install_session_lock_cleanup() {
  trap 'cleanup_session_lock' EXIT
  trap 'exit 1' HUP INT TERM
}

# Keep stderr separate: a diagnostic is an error even if ls exits successfully.
read_private_directory() {
  local path="$1" error_file output status=0
  error_file="$(create_private_temp_file /private/tmp/issue86-list-error.XXXXXXXX)" || return 1
  output="$("$adb_bin" -s "$serial" shell run-as "$target" ls -1a "$path" 2>"$error_file")" || status=$?
  if [[ "$status" != 0 || -s "$error_file" ]]; then
    rm -f -- "$error_file"
    return 1
  fi
  rm -f -- "$error_file" || return 1
  printf '%s\n' "$output"
}

validate_private_directory_listing() {
  [[ -n "$1" && "$1" != *$'\r'* ]] || return 1
  printf '%s\n' "$1" | awk '
    NR == 1 { if ($0 != ".") bad=1; next }
    NR == 2 { if ($0 != "..") bad=1; next }
    NR > 2 { if ($0 == "" || $0 == "." || $0 == "..") bad=1 }
    END { exit (NR < 2 || bad) }
  '
}

# Read the lstat-style type character without following the app-private entry.
read_private_entry_type() {
  local path="$1" error_file output status=0 mode
  case "$path" in
    files|files/issue86_process_death|files/pending_uploads|files/issue86_process_death/issue86-pd-*|files/issue86_process_death/issue86-pd-*/checkpoint-v1.json) ;;
    *) return 1 ;;
  esac
  error_file="$(create_private_temp_file /private/tmp/issue86-type-error.XXXXXXXX)" || return 1
  output="$("$adb_bin" -s "$serial" shell run-as "$target" ls -ld "$path" 2>"$error_file")" || status=$?
  if [[ "$status" != 0 || -s "$error_file" || -z "$output" || "$output" == *$'\n'* || "$output" == *$'\r'* ]]; then
    rm -f -- "$error_file" || return 1
    return 1
  fi
  rm -f -- "$error_file" || return 1
  mode="${output%% *}"
  [[ "${output##* }" == "$path" && "$mode" =~ ^[dlbcps-][rwxstST-]{9}[+.@]?$ ]] || return 1
  case "${mode:0:1}" in
    d) printf '%s\n' DIRECTORY ;;
    l) printf '%s\n' SYMLINK ;;
    -) printf '%s\n' REGULAR ;;
    *) printf '%s\n' OTHER ;;
  esac
}

verify_run_as_identity() {
  local output
  output="$("$adb_bin" -s "$serial" shell run-as "$target" id 2>&1)" || return 1
  [[ -n "$output" && "$output" != *$'\n'* && "$output" != *$'\r'* &&
     "$output" =~ ^uid=[0-9]+\([^()[:space:]]+\)([[:space:]]+gid=[0-9]+\([^()[:space:]]+\))?([[:space:]]+groups=[0-9,]+\([^()[:space:]]+\)(,[0-9]+\([^()[:space:]]+\))*)?([[:space:]]+context=[^[:space:]]+)?$ ]]
}

read_private_checkpoint() {
  local path="$1" session_id="$2" scenario="$3" error_file result status=0
  case "$path" in
    files/issue86_process_death/issue86-pd-*/checkpoint-v1.json) ;;
    *) return 1 ;;
  esac
  error_file="$(create_private_temp_file /private/tmp/issue86-file-error.XXXXXXXX)" || return 1
  result="$("$adb_bin" -s "$serial" shell run-as "$target" cat "$path" 2>"$error_file" |
    validate_phase1_checkpoint_stream "$session_id" "$scenario")" || status=$?
  if [[ "$status" != 0 || -s "$error_file" ]]; then
    rm -f -- "$error_file" || return 1
    return 1
  fi
  rm -f -- "$error_file" || return 1
  case "$result" in
    $'VALID\t'[1-9][0-9]*)
      phase1_checkpoint_pid="${result#*$'\t'}"
      [[ "$phase1_checkpoint_pid" =~ ^[1-9][0-9]*$ ]] || return 1
      return 0
      ;;
    INVALID) return 2 ;;
    *) return 1 ;;
  esac
}

# Parse the entire app-private checkpoint from stdin. Extra keys stay allowed,
# matching JSONObject's forward-compatible readers, while duplicate keys fail.
validate_phase1_checkpoint_stream() {
  local session_id="$1" scenario="$2"
  command -v python3 >/dev/null 2>&1 || return 127
  python3 -c '
import hashlib
import json
import re
import sys

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON object key")
        result[key] = value
    return result

def reject_constant(value):
    raise ValueError("non-standard JSON constant")

def remote_value_bytes(data):
    """Return the exact source bytes for the top-level remote JSON object."""
    def whitespace(position):
        while position < len(data) and data[position] in b" \t\r\n":
            position += 1
        return position

    def string_end(position):
        position += 1
        while position < len(data):
            byte = data[position]
            if byte == 0x5c:
                position += 2
            elif byte == 0x22:
                return position + 1
            else:
                position += 1
        raise ValueError("unterminated JSON string")

    def value_end(position):
        position = whitespace(position)
        if position >= len(data):
            raise ValueError("missing JSON value")
        if data[position] == 0x22:
            return string_end(position)
        if data[position] == 0x7b:
            position = whitespace(position + 1)
            if position < len(data) and data[position] == 0x7d:
                return position + 1
            while True:
                if position >= len(data) or data[position] != 0x22:
                    raise ValueError("invalid JSON object key")
                position = whitespace(string_end(position))
                if position >= len(data) or data[position] != 0x3a:
                    raise ValueError("missing JSON object colon")
                position = value_end(position + 1)
                position = whitespace(position)
                if position < len(data) and data[position] == 0x7d:
                    return position + 1
                if position >= len(data) or data[position] != 0x2c:
                    raise ValueError("invalid JSON object separator")
                position = whitespace(position + 1)
        if data[position] == 0x5b:
            position = whitespace(position + 1)
            if position < len(data) and data[position] == 0x5d:
                return position + 1
            while True:
                position = whitespace(value_end(position))
                if position < len(data) and data[position] == 0x5d:
                    return position + 1
                if position >= len(data) or data[position] != 0x2c:
                    raise ValueError("invalid JSON array separator")
                position += 1
        while position < len(data) and data[position] not in b" \t\r\n,]}:":
            position += 1
        return position

    position = whitespace(0)
    if position >= len(data) or data[position] != 0x7b:
        raise ValueError("checkpoint root is not an object")
    position = whitespace(position + 1)
    while position < len(data) and data[position] != 0x7d:
        key_start = position
        key_end = string_end(key_start)
        key = json.loads(data[key_start:key_end].decode("utf-8"))
        position = whitespace(key_end)
        if position >= len(data) or data[position] != 0x3a:
            raise ValueError("missing JSON object colon")
        value_start = whitespace(position + 1)
        end = value_end(value_start)
        if key == "remote":
            if data[value_start] != 0x7b:
                raise ValueError("remote evidence is not an object")
            return data[value_start:end]
        position = whitespace(end)
        if position < len(data) and data[position] == 0x2c:
            position = whitespace(position + 1)
        elif position < len(data) and data[position] == 0x7d:
            break
        else:
            raise ValueError("invalid JSON object separator")
    raise ValueError("missing remote evidence")

def invalid():
    print("INVALID")
    raise SystemExit(0)

session, scenario = sys.argv[1:3]
try:
    raw = sys.stdin.buffer.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        invalid()
    checkpoint = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=reject_constant)
except (ValueError, UnicodeError, RecursionError):
    invalid()

if type(checkpoint) is not dict:
    invalid()

application_id = "com.negi.survey.issue86.it"
if not re.fullmatch(r"issue86-pd-[A-Za-z0-9_-]{8,80}", session):
    invalid()
if scenario not in ("REMOTE_CONFIRMED_BEFORE_LEDGER", "LEDGER_COMMITTED_BEFORE_CLEANUP"):
    invalid()

required = (
    "version", "session", "scenario", "applicationId", "surveyId", "canonicalPath",
    "payloadFingerprint", "phase1Pid", "processName", "terminationMode",
    "checkpointEpochMs", "ready", "boundary", "readyPid", "remote",
    "remoteDigest", "terminationIntent",
)
if any(key not in checkpoint for key in required):
    invalid()

def string_field(key, expected=None, nonempty=True):
    value = checkpoint[key]
    if type(value) is not str or (nonempty and not value):
        invalid()
    if expected is not None and value != expected:
        invalid()
    return value

def integer_field(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum

if type(checkpoint["version"]) is not int or checkpoint["version"] != 2:
    invalid()
string_field("session", session)
string_field("scenario", scenario)
string_field("applicationId", application_id)
string_field("processName", application_id)
string_field("terminationMode", "SELF_PROCESS_KILL")
string_field("terminationIntent", "RECORDED")
string_field("boundary", scenario)
if type(checkpoint["ready"]) is not bool or checkpoint["ready"] is not True:
    invalid()

survey_id = string_field("surveyId", "issue86-process-death-" + session)
normalized_survey_id = survey_id.strip().lower()
canonical_path = "exports/surveys/" + hashlib.sha256(normalized_survey_id.encode("utf-8")).hexdigest() + ".json"
string_field("canonicalPath", canonical_path)
if not re.fullmatch(r"[0-9a-f]{64}", string_field("payloadFingerprint")):
    invalid()
if not re.fullmatch(r"[0-9a-f]{64}", string_field("remoteDigest")):
    invalid()

max_pid = 2**31 - 1
phase1_pid = checkpoint["phase1Pid"]
ready_pid = checkpoint["readyPid"]
if not integer_field(phase1_pid, 1, max_pid) or not integer_field(ready_pid, 1, max_pid):
    invalid()
if ready_pid != phase1_pid:
    invalid()
if not integer_field(checkpoint["checkpointEpochMs"], 1, 2**63 - 1):
    invalid()

remote = checkpoint["remote"]
if type(remote) is not dict:
    invalid()
for key in ("version", "path", "content", "createCount", "lookupCount", "conflictCount"):
    if key not in remote:
        invalid()
if type(remote["version"]) is not int or remote["version"] != 1:
    invalid()
if type(remote["path"]) is not str or remote["path"] != canonical_path:
    invalid()
if type(remote["content"]) is not str or not remote["content"].strip():
    invalid()
if hashlib.sha256(remote["content"].encode("utf-8")).hexdigest() != checkpoint["payloadFingerprint"]:
    invalid()
try:
    serialized_remote = remote_value_bytes(raw)
except (ValueError, UnicodeError, RecursionError):
    invalid()
if hashlib.sha256(serialized_remote).hexdigest() != checkpoint["remoteDigest"]:
    invalid()
if (type(remote["createCount"]) is not int or remote["createCount"] != 1 or
        type(remote["lookupCount"]) is not int or remote["lookupCount"] != 0 or
        type(remote["conflictCount"]) is not int or remote["conflictCount"] != 0):
    invalid()

# JSONObject readers ignore unknown fields; preserve that forward-compatible behavior.
# The parsed PID and readiness decision both come from this one JSON snapshot.
print("VALID\t" + str(ready_pid))
' "$session_id" "$scenario"
}

# Distinguish a not-yet-published checkpoint from inspection errors by walking
# only successfully listed parent directories. Missing entries are pending;
# malformed listings, symlinks, special files, ADB, and run-as failures fail.
read_phase1_checkpoint() {
  local session_id="$1" scenario="$2"
  local root_listing files_listing recovery_listing session_listing path_type path checkpoint_status
  phase1_checkpoint_state=ERROR
  phase1_checkpoint_pid=""
  valid_session_id "$session_id" && verify_run_as_identity || return 1

  root_listing="$(read_private_directory .)" || return 1
  validate_private_directory_listing "$root_listing" || return 1
  if ! printf '%s\n' "$root_listing" | grep -Fxq files; then
    phase1_checkpoint_state=PENDING
    return 0
  fi
  path_type="$(read_private_entry_type files)" || return 1
  [[ "$path_type" == DIRECTORY ]] || return 1
  files_listing="$(read_private_directory files)" || return 1
  validate_private_directory_listing "$files_listing" || return 1
  if ! printf '%s\n' "$files_listing" | grep -Fxq issue86_process_death; then
    phase1_checkpoint_state=PENDING
    return 0
  fi
  path_type="$(read_private_entry_type files/issue86_process_death)" || return 1
  [[ "$path_type" == DIRECTORY ]] || return 1
  recovery_listing="$(read_private_directory files/issue86_process_death)" || return 1
  validate_private_directory_listing "$recovery_listing" || return 1
  if ! printf '%s\n' "$recovery_listing" | grep -Fxq "$session_id"; then
    phase1_checkpoint_state=PENDING
    return 0
  fi
  path="files/issue86_process_death/$session_id"
  path_type="$(read_private_entry_type "$path")" || return 1
  [[ "$path_type" == DIRECTORY ]] || return 1
  session_listing="$(read_private_directory "$path")" || return 1
  validate_private_directory_listing "$session_listing" || return 1
  if ! printf '%s\n' "$session_listing" | grep -Fxq checkpoint-v1.json; then
    phase1_checkpoint_state=PENDING
    return 0
  fi
  path="$path/checkpoint-v1.json"
  path_type="$(read_private_entry_type "$path")" || return 1
  [[ "$path_type" == REGULAR ]] || return 1
  if read_private_checkpoint "$path" "$session_id" "$scenario"; then
    phase1_checkpoint_state=READY
  else
    checkpoint_status=$?
    if [[ "$checkpoint_status" == 2 ]]; then
      phase1_checkpoint_state=INVALID
      return 0
    fi
    return 1
  fi
  return 0
}

# Returns one of SESSION_ABSENT, SESSION_EXISTS, or SESSION_CHECK_ERROR.
# Inspect directory entries rather than treating a failed test -e as absence.
# A successful readable listing establishes absence without relying on errno.
classify_session_checkpoint() {
  local session_id="$1"
  local root_listing files_listing session_root_listing session_listing pending_listing path_type session_dir
  valid_session_id "$session_id" || { printf '%s\n' SESSION_CHECK_ERROR; return; }

  if ! verify_run_as_identity; then
    printf '%s\n' SESSION_CHECK_ERROR
    return
  fi

  if ! root_listing="$(read_private_directory .)" || ! validate_private_directory_listing "$root_listing"; then
    printf '%s\n' SESSION_CHECK_ERROR
    return
  fi
  # A successful listing of the accessible package root proves that the exact
  # files entry is absent; a failed listing never means absence.
  if ! printf '%s\n' "$root_listing" | grep -Fxq files; then
    printf '%s\n' SESSION_ABSENT
    return
  fi

  path_type="$(read_private_entry_type files)" || path_type=UNKNOWN
  [[ "$path_type" == DIRECTORY ]] || {
    printf '%s\n' SESSION_CHECK_ERROR
    return
  }
  if ! files_listing="$(read_private_directory files)" || ! validate_private_directory_listing "$files_listing"; then
    printf '%s\n' SESSION_CHECK_ERROR
    return
  fi
  if printf '%s\n' "$files_listing" | grep -Fxq 'issue86_process_death'; then
    path_type="$(read_private_entry_type files/issue86_process_death)" || path_type=UNKNOWN
    [[ "$path_type" == DIRECTORY ]] || {
      printf '%s\n' SESSION_CHECK_ERROR
      return
    }
    if ! session_root_listing="$(read_private_directory files/issue86_process_death)" ||
       ! validate_private_directory_listing "$session_root_listing"; then
      printf '%s\n' SESSION_CHECK_ERROR
      return
    fi
    if printf '%s\n' "$session_root_listing" | grep -Fxq "$session_id"; then
      session_dir="files/issue86_process_death/$session_id"
      path_type="$(read_private_entry_type "$session_dir")" || path_type=UNKNOWN
      [[ "$path_type" == DIRECTORY ]] || {
        printf '%s\n' SESSION_CHECK_ERROR
        return
      }
      if ! session_listing="$(read_private_directory "$session_dir")" ||
         ! validate_private_directory_listing "$session_listing"; then
        printf '%s\n' SESSION_CHECK_ERROR
        return
      fi
      if printf '%s\n' "$session_listing" | grep -Fxq checkpoint-v1.json; then
        path_type="$(read_private_entry_type "$session_dir/checkpoint-v1.json")" || path_type=UNKNOWN
        [[ "$path_type" == REGULAR ]] || {
          printf '%s\n' SESSION_CHECK_ERROR
          return
        }
      fi
      # Existing and partial session directories both retain the original
      # conflict policy; neither is eligible to start as a fresh session.
      printf '%s\n' SESSION_EXISTS
      return
    fi
  fi
  if printf '%s\n' "$files_listing" | grep -Fxq 'pending_uploads'; then
    path_type="$(read_private_entry_type files/pending_uploads)" || path_type=UNKNOWN
    [[ "$path_type" == DIRECTORY ]] || {
      printf '%s\n' SESSION_CHECK_ERROR
      return
    }
    if ! pending_listing="$(read_private_directory files/pending_uploads)" ||
       ! validate_private_directory_listing "$pending_listing"; then
      printf '%s\n' SESSION_CHECK_ERROR
      return
    fi
    if printf '%s\n' "$pending_listing" | grep -Fxq -e "$session_id-a.json" -e "$session_id-b.json"; then
      printf '%s\n' SESSION_EXISTS
      return
    fi
  fi
  printf '%s\n' SESSION_ABSENT
}

# Classify toybox ps -A -o PID,NAME output. A header-only response cannot
# establish absence: an all-process listing should contain process records.
classify_process_listing() {
  perl -e '
    use strict; use warnings;
    my $target = shift;
    sub unknown { print "UNKNOWN\n"; exit 0 }
    unknown() unless defined($target) && $target =~ /\A[0-9]{1,10}\z/ && $target > 0;
    binmode STDIN;
    my $data = "";
    while (1) {
      my $n = sysread(STDIN, my $chunk, 65536);
      unknown() unless defined $n;
      last if $n == 0;
      $data .= $chunk;
      unknown() if length($data) > 16 * 1024 * 1024;
    }
    unknown() unless length($data) && $data =~ /\n\z/ && $data !~ /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/;
    my @lines = split /\n/, $data;
    for (@lines) { s/\r\z//; unknown() if /\r/; }
    my $header = shift @lines;
    unknown() unless defined($header) && $header =~ /\A[ \t]*PID[ \t]+NAME[ \t]*\z/;
    unknown() unless @lines;
    my (%seen, $found);
    for my $line (@lines) {
      unknown() unless $line =~ /\A[ \t]*([0-9]{1,10})[ \t]+([^ \t].*)\z/;
      my $pid = 0 + $1;
      unknown() if !$pid || $seen{$pid}++;
      $found = 1 if $pid == 0 + $target;
    }
    print $found ? "PRESENT\n" : "ABSENT\n";
  ' "$1"
}

# A failed ADB/ps command must override any parser output.
classify_phase1_process() {
  local result error_file status=0
  error_file="$(create_private_temp_file /private/tmp/issue86-ps-error.XXXXXXXX)" || { printf '%s\n' UNKNOWN; return; }
  result="$(adb -s "$serial" shell ps -A -o PID,NAME 2>"$error_file" | classify_process_listing "$1")" || status=$?
  if [[ "$status" != 0 || -s "$error_file" ]]; then
    if ! rm -f -- "$error_file"; then echo "could not remove process-list diagnostic file" >&2; fi
    printf '%s\n' UNKNOWN
    return
  fi
  if ! rm -f -- "$error_file"; then printf '%s\n' UNKNOWN; return; fi
  case "$result" in
    PRESENT|ABSENT|UNKNOWN) printf '%s\n' "$result" ;;
    *) printf '%s\n' UNKNOWN ;;
  esac
}

# Classify a single snapshot from the reserved reader, never a log pathname.
classify_phase1_completion() {
  local data="$1" wait_status="$2" checkpoint_verified="$3" process_gone="$4"
  [[ "$checkpoint_verified" == true && "$process_gone" == true && "$wait_status" == 0 && -n "$data" ]] || {
    printf '%s\n' REJECT; return
  }
  if awk '
    $0 == "INSTRUMENTATION_RESULT: shortMsg=Process crashed." { result++ }
    /^INSTRUMENTATION_RESULT:/ { results++ }
    $0 == "INSTRUMENTATION_CODE: 0" { code++ }
    /^INSTRUMENTATION_CODE:/ { codes++ }
    /^[[:space:]]*INSTRUMENTATION_STATUS_CODE/ && $0 != "INSTRUMENTATION_STATUS_CODE: 1" { bad=1 }
    /^[[:space:]]*INSTRUMENTATION_(RESULT|CODE)/ && $0 !~ /^INSTRUMENTATION_(RESULT|CODE):/ { bad=1 }
    /InvalidTestClassError|FAILURES!!!|AssertionError|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS: (stack|shortMsg|longMsg)=/ { bad=1 }
    NF { last=$0 }
    END { exit !( !bad && result==1 && results==1 && code==1 && codes==1 && last=="INSTRUMENTATION_CODE: 0" ) }
  ' <<< "$data"; then printf '%s\n' ACCEPT_FOR_PHASE_2; else printf '%s\n' REJECT; fi
}

# Require a completed raw AndroidJUnitRunner execution of exactly the selected test.
classify_phase2_completion() {
  local data="$1" wait_status="$2" method="$3"
  [[ "$wait_status" == 0 && -n "$data" ]] || { printf '%s\n' REJECT; return; }
  if awk -v method="$method" '
    BEGIN { cls="com.negi.survey.issue86.Issue86ProcessDeathPhase2Test" }
    /^[[:space:]]*INSTRUMENTATION_STATUS_CODE/ {
      if ($0 == "INSTRUMENTATION_STATUS_CODE: 1" && status == 0) { status=1; next }
      if ($0 == "INSTRUMENTATION_STATUS_CODE: 0" && status == 1) { status=2; next }
      bad=1
    }
    /^INSTRUMENTATION_STATUS: class=/ { if ($0 != "INSTRUMENTATION_STATUS: class=" cls) bad=1; classes++ }
    /^INSTRUMENTATION_STATUS: test=/ { if ($0 != "INSTRUMENTATION_STATUS: test=" method) bad=1; tests++ }
    /^INSTRUMENTATION_STATUS: numtests=/ { if ($0 != "INSTRUMENTATION_STATUS: numtests=1") bad=1; counts++ }
    /^[[:space:]]*INSTRUMENTATION_(RESULT|CODE)/ {
      if ($0 == "INSTRUMENTATION_RESULT: stream=") results++
      else if ($0 == "INSTRUMENTATION_CODE: -1") codes++
      else bad=1
    }
    /^OK \(1 test\)$/ { oks++ }
    /InvalidTestClassError|FAILURES!!!|AssertionError|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS: (stack|shortMsg|longMsg)=/ { bad=1 }
    NF { last=$0 }
    END { exit !( !bad && status==2 && classes==2 && tests==2 && counts==2 && results==1 && codes==1 && oks==1 && last=="INSTRUMENTATION_CODE: -1" ) }
  ' <<< "$data"; then printf '%s\n' ACCEPT; else printf '%s\n' REJECT; fi
}

# Allows the focused mock-ADB regression test to import only the pure runner helpers.
if [[ "${ISSUE86_RUNNER_LIB_ONLY:-false}" == true ]]; then
  adb_bin="${ISSUE86_ADB_BIN:-adb}"
  return 0 2>/dev/null || exit 0
fi

valid_session_id "$session" || { echo "invalid session" >&2; exit 64; }
if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import json' >/dev/null; then
  echo "Python 3 with the standard JSON parser is required for Phase 1 checkpoint validation" >&2
  exit 69
fi
configure_host_session_paths "$session" || { echo "could not configure host evidence paths" >&2; exit 1; }
acquire_session_lock || { echo "session execution lock is unavailable" >&2; exit 1; }
install_session_lock_cleanup
case "$(classify_host_evidence)" in
  HOST_EVIDENCE_UNUSED) ;;
  HOST_EVIDENCE_EXISTS) echo "conflicting existing host evidence" >&2; exit 1 ;;
  *) echo "could not verify host evidence precondition" >&2; exit 1 ;;
esac
[[ "$scenario" == REMOTE_CONFIRMED_BEFORE_LEDGER || "$scenario" == LEDGER_COMMITTED_BEFORE_CLEANUP ]] || {
  echo "invalid scenario" >&2; exit 64;
}
[[ "$execute" == --execute ]] || { echo "refusing without --execute" >&2; exit 64; }
adb -s "$serial" get-state | grep -qx device
[[ "$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')" == SM-S731U ]]
adb -s "$serial" shell pm path "$target" | grep -q '^package:'
adb -s "$serial" shell pm path "$testpkg" | grep -q '^package:'
adb -s "$serial" shell cmd package list instrumentation |
  grep -Fxq "instrumentation:${testpkg}/androidx.test.runner.AndroidJUnitRunner (target=${target})"
case "$(classify_session_checkpoint "$session")" in
  SESSION_ABSENT) ;;
  SESSION_EXISTS) echo "conflicting existing session" >&2; exit 1 ;;
  *) echo "could not verify session precondition" >&2; exit 1 ;;
esac
reserve_host_evidence || { echo "could not reserve host evidence" >&2; exit 1; }

runner="${testpkg}/androidx.test.runner.AndroidJUnitRunner"
phase1_method="com.negi.survey.issue86.Issue86ProcessDeathPhase1Test#remoteConfirmedBeforeLedger_selfTerminatesAfterCheckpoint"
[[ "$scenario" != LEDGER_COMMITTED_BEFORE_CLEANUP ]] ||
  phase1_method="com.negi.survey.issue86.Issue86ProcessDeathPhase1Test#ledgerCommittedBeforeCleanup_selfTerminatesAfterCheckpoint"
session_operation_uncertain=true
adb -s "$serial" shell am instrument -w -r \
  -e clearPackageData false \
  -e disableDiagnosticUpload true \
  -e issue86ProcessDeathSession "$session" \
  -e issue86ProcessDeathScenario "$scenario" \
  -e class "$phase1_method" "$runner" >&3 2>&1 &
phase1_job=$!

phase1_pid=""
for ((attempt = 1; attempt <= checkpoint_wait_seconds; attempt++)); do
  if ! read_phase1_checkpoint "$session" "$scenario"; then
    echo "could not inspect Phase 1 checkpoint" >&2
    exit 1
  fi
  case "$phase1_checkpoint_state" in
    READY)
      phase1_pid="$phase1_checkpoint_pid"
      break
      ;;
    PENDING) sleep 1 ;;
    INVALID) echo "Phase 1 checkpoint is malformed or belongs to another session" >&2; exit 1 ;;
    *) echo "could not inspect Phase 1 checkpoint" >&2; exit 1 ;;
  esac
done
[[ -n "$phase1_pid" ]] || { echo "durable self-termination checkpoint not ready" >&2; exit 1; }

process_gone=false
for ((attempt = 1; attempt <= exit_wait_seconds; attempt++)); do
  case "$(classify_phase1_process "$phase1_pid")" in
    ABSENT) process_gone=true; break ;;
    PRESENT) sleep 1 ;;
    *) echo "could not verify Phase 1 process listing" >&2; exit 1 ;;
  esac
done
[[ "$process_gone" == true ]] || { echo "Phase 1 target process did not disappear" >&2; exit 1; }

phase1_exited=false
for ((attempt = 1; attempt <= exit_wait_seconds; attempt++)); do
  if ! jobs -pr | grep -Fxq "$phase1_job"; then
    phase1_exited=true
    break
  fi
  sleep 1
done
[[ "$phase1_exited" == true ]] || { echo "Phase 1 instrumentation did not exit within bound" >&2; exit 1; }
phase1_wait_status=0
if wait "$phase1_job"; then
  :
else
  phase1_wait_status=$?
fi
phase1_data="$(read_reserved_evidence 1 && printf '.')" || { echo "could not read Phase 1 evidence" >&2; exit 1; }
phase1_data="${phase1_data%.}"
case "$(classify_phase1_completion "$phase1_data" "$phase1_wait_status" true "$process_gone")" in
  ACCEPT_FOR_PHASE_2) ;;
  *) echo "Phase 1 instrumentation did not prove expected self-termination" >&2; exit 1 ;;
esac

phase2_method="recoverRemoteConfirmedBeforeLedger"
[[ "$scenario" != LEDGER_COMMITTED_BEFORE_CLEANUP ]] || phase2_method="recoverLedgerCommittedBeforeCleanup"
phase2_status=0
adb -s "$serial" shell am instrument -w -r \
  -e clearPackageData false \
  -e disableDiagnosticUpload true \
  -e issue86ProcessDeathSession "$session" \
  -e issue86ProcessDeathScenario "$scenario" \
  -e class "com.negi.survey.issue86.Issue86ProcessDeathPhase2Test#$phase2_method" "$runner" >&4 2>&1 &
phase2_job=$!
phase2_exited=false
for ((attempt = 1; attempt <= checkpoint_wait_seconds; attempt++)); do
  if ! jobs -pr | grep -Fxq "$phase2_job"; then
    phase2_exited=true
    break
  fi
  sleep 1
done
[[ "$phase2_exited" == true ]] || { echo "Phase 2 instrumentation did not exit within bound" >&2; exit 1; }
wait "$phase2_job" || phase2_status=$?
phase2_data="$(read_reserved_evidence 2 && printf '.')" || { echo "could not read Phase 2 evidence" >&2; exit 1; }
phase2_data="${phase2_data%.}"
printf '%s' "$phase2_data"
[[ "$(classify_phase2_completion "$phase2_data" "$phase2_status" "$phase2_method")" == ACCEPT ]] || {
  echo "Phase 2 instrumentation did not report an unambiguous passing test" >&2; exit 1;
}
session_operation_uncertain=false
