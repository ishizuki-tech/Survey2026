#!/usr/bin/env bash
# Focused mock-ADB regression coverage for the runner's session preflight.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d /private/tmp/issue86-runner-test-XXXXXX)"
trap 'rm -rf "$tmp"' EXIT
fake_adb="$tmp/adb"
log="$tmp/adb.log"

cat >"$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >>"${ISSUE86_ADB_LOG:?}"
case "${ISSUE86_FAKE_MODE:?}:$*" in
  unavailable:*) exit 127 ;;
  missing:*' run-as com.negi.survey.issue86.it id') echo 'run-as: Package not found' >&2; exit 1 ;;
  permission:*' run-as com.negi.survey.issue86.it id') echo 'run-as: Package not debuggable' >&2; exit 1 ;;
  empty:*' run-as com.negi.survey.issue86.it id') exit 0 ;;
  malformed:*' run-as com.negi.survey.issue86.it id') echo 'not-an-id'; exit 0 ;;
  transport:*' run-as com.negi.survey.issue86.it id') echo 'error: device offline' >&2; exit 1 ;;
  *' run-as com.negi.survey.issue86.it id') echo 'uid=10362(u0_a362)'; exit 0 ;;
esac
case "${ISSUE86_FAKE_MODE:?}:$*" in
  root_unexpected:*' ls -1a .') exit 2 ;;
  root_output:*' ls -1a .') printf 'not-a-directory-listing\n'; exit 0 ;;
  root_warning:*' ls -1a .') printf '.\n..\n'; echo 'unexpected diagnostic' >&2; exit 0 ;;
  root_permission:*' ls -1a .') echo 'Permission denied' >&2; exit 1 ;;
  root_transport:*' ls -1a .') echo 'error: device offline' >&2; exit 1 ;;
  root_files_type:*' ls -1a .') printf '.\n..\nfiles\n'; exit 0 ;;
  absent:*' ls -1a .') printf '.\n..\n'; exit 0 ;;
  *' ls -1a .') printf '.\n..\nfiles\n'; exit 0 ;;
  files_symlink:*' ls -ld files') printf 'lrwxrwxrwx 1 u0_a362 u0_a362 12 2026-11-03 10:00 files -> /tmp/target\n'; exit 0 ;;
  files_other:*' ls -ld files') printf '%s\n' '-rw------- 1 u0_a362 u0_a362 12 2026-11-03 10:00 files'; exit 0 ;;
  files_special:*' ls -ld files') printf '%s\n' 'prw------- 1 u0_a362 u0_a362 0 2026-11-03 10:00 files'; exit 0 ;;
  files_bad_output:*' ls -ld files') printf 'unexpected file-type response\n'; exit 0 ;;
  nested_symlink:*' ls -ld files/issue86_process_death') printf 'lrwxrwxrwx 1 u0_a362 u0_a362 12 2026-11-03 10:00 files/issue86_process_death -> /tmp/target\n'; exit 0 ;;
  nested_other:*' ls -ld files/pending_uploads') printf '%s\n' '-rw------- 1 u0_a362 u0_a362 12 2026-11-03 10:00 files/pending_uploads'; exit 0 ;;
  *' ls -ld files') printf '%s\n' 'drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files'; exit 0 ;;
  *' ls -ld files/issue86_process_death') printf '%s\n' 'drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files/issue86_process_death'; exit 0 ;;
  *' ls -ld files/pending_uploads') printf '%s\n' 'drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files/pending_uploads'; exit 0 ;;
  *' ls -ld files/issue86_process_death/issue86-pd-Regression_1234') printf '%s\n' 'drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files/issue86_process_death/issue86-pd-Regression_1234'; exit 0 ;;
  checkpoint_symlink:*' ls -ld files/issue86_process_death/issue86-pd-Regression_1234/checkpoint-v1.json') printf 'lrwxrwxrwx 1 u0_a362 u0_a362 12 2026-11-03 10:00 files/issue86_process_death/issue86-pd-Regression_1234/checkpoint-v1.json -> /tmp/target\n'; exit 0 ;;
  *' ls -ld files/issue86_process_death/issue86-pd-Regression_1234/checkpoint-v1.json') printf '%s\n' '-rw------- 1 u0_a362 u0_a362 256 2026-11-03 10:00 files/issue86_process_death/issue86-pd-Regression_1234/checkpoint-v1.json'; exit 0 ;;
  inaccessible:*' ls -1a files') echo 'Permission denied' >&2; exit 1 ;;
  root_files_type:*' ls -1a files') printf '.\n..\n'; exit 0 ;;
  *' ls -1a files') printf '.\n..\nissue86_process_death\npending_uploads\n'; exit 0 ;;
  exists:*' ls -1a files/issue86_process_death'|checkpoint:*' ls -1a files/issue86_process_death'|checkpoint_malformed:*' ls -1a files/issue86_process_death'|checkpoint_unreadable:*' ls -1a files/issue86_process_death'|checkpoint_symlink:*' ls -1a files/issue86_process_death'|fake_remote:*' ls -1a files/issue86_process_death') printf '.\n..\nissue86-pd-Regression_1234\n'; exit 0 ;;
  checkpoint_unreadable:*' ls -1a files/issue86_process_death/issue86-pd-Regression_1234') echo 'Permission denied' >&2; exit 1 ;;
  checkpoint_malformed:*' ls -1a files/issue86_process_death/issue86-pd-Regression_1234') printf 'checkpoint-v1.json\n'; exit 0 ;;
  checkpoint:*' ls -1a files/issue86_process_death/issue86-pd-Regression_1234') printf '.\n..\ncheckpoint-v1.json\n'; exit 0 ;;
  checkpoint_symlink:*' ls -1a files/issue86_process_death/issue86-pd-Regression_1234') printf '.\n..\ncheckpoint-v1.json\n'; exit 0 ;;
  *' ls -1a files/issue86_process_death/issue86-pd-Regression_1234') printf '.\n..\n'; exit 0 ;;
  directory_absent:*' ls -1a files') printf '.\n..\n'; exit 0 ;;
  *' ls -1a files/issue86_process_death') printf '.\n..\n'; exit 0 ;;
  staged_a:*' ls -1a files/pending_uploads') printf '.\n..\nissue86-pd-Regression_1234-a.json\n'; exit 0 ;;
  staged_b:*' ls -1a files/pending_uploads') printf '.\n..\nissue86-pd-Regression_1234-b.json\n'; exit 0 ;;
  staged_both:*' ls -1a files/pending_uploads') printf '.\n..\nissue86-pd-Regression_1234-a.json\nissue86-pd-Regression_1234-b.json\n'; exit 0 ;;
  *' ls -1a files/pending_uploads') printf '.\n..\n'; exit 0 ;;
  *) echo "unexpected fake adb invocation: $*" >&2; exit 99 ;;
esac
EOF
chmod 700 "$fake_adb"

export ISSUE86_RUNNER_LIB_ONLY=true ISSUE86_ADB_BIN="$fake_adb" ISSUE86_ADB_LOG="$log"
# shellcheck source=issue86-process-death-runner.sh
source "$root/tools/issue86-process-death-runner.sh"

session='issue86-pd-Regression_1234'
assert_equals() { [[ "$1" == "$2" ]] || { echo "expected '$1', got '$2'" >&2; exit 1; }; }
assert_fails() { if "$@"; then echo "expected failure: $*" >&2; exit 1; fi; }
parser_fixture="$(python3 - "$session" <<'PY'
import hashlib, json, sys
session=sys.argv[1]
survey='issue86-process-death-'+session
canonical='exports/surveys/'+hashlib.sha256(survey.lower().encode()).hexdigest()+'.json'
content=json.dumps({'survey_id':'synthetic','note':'café / \\ "line"'},separators=(',',':'),ensure_ascii=False)
remote={'version':1,'path':canonical,'content':content,'createCount':1,'lookupCount':0,'conflictCount':0}
def android_quote(value):
    return json.dumps(value,ensure_ascii=False)[1:-1].replace('/', r'\/')
def android_remote(value):
    return '{"version":1,"path":"'+android_quote(value['path'])+'","content":"'+android_quote(value['content'])+'","createCount":'+str(value['createCount'])+',"lookupCount":'+str(value['lookupCount'])+',"conflictCount":'+str(value['conflictCount'])+'}'
remote_text=android_remote(remote)
checkpoint={
 'version':2,'session':session,'scenario':'REMOTE_CONFIRMED_BEFORE_LEDGER',
 'applicationId':'com.negi.survey.issue86.it','surveyId':survey,'canonicalPath':canonical,
 'payloadFingerprint':hashlib.sha256(content.encode('utf-8')).hexdigest(),'phase1Pid':12345,'processName':'com.negi.survey.issue86.it',
 'terminationMode':'SELF_PROCESS_KILL','checkpointEpochMs':1793577600000,'ready':True,
 'boundary':'REMOTE_CONFIRMED_BEFORE_LEDGER','readyPid':12345,
 'remote':remote,
 'remoteDigest':hashlib.sha256(remote_text.encode('utf-8')).hexdigest(),'terminationIntent':'RECORDED'}
text=json.dumps(checkpoint,separators=(',',':'),ensure_ascii=False)
python_remote=json.dumps(remote,separators=(',',':'),ensure_ascii=False)
text=text.replace('"remote":'+python_remote,'"remote":'+remote_text,1)
print(text)
PY
)"
assert_equals $'VALID\t12345' "$(printf '%s' "$parser_fixture" | validate_phase1_checkpoint_stream "$session" REMOTE_CONFIRMED_BEFORE_LEDGER)"
for integrity_case in payload remote_digest remote_content; do
  invalid_fixture="$(python3 - "$session" "$integrity_case" <<'PY'
import hashlib, json, sys
session, case=sys.argv[1:]
survey='issue86-process-death-'+session
canonical='exports/surveys/'+hashlib.sha256(survey.lower().encode()).hexdigest()+'.json'
content=json.dumps({'survey_id':'synthetic','note':'café / \\ "line"'},separators=(',',':'),ensure_ascii=False)
remote={'version':1,'path':canonical,'content':content,'createCount':1,'lookupCount':0,'conflictCount':0}
def android_quote(value):
    return json.dumps(value,ensure_ascii=False)[1:-1].replace('/', r'\/')
def android_remote(value):
    return '{"version":1,"path":"'+android_quote(value['path'])+'","content":"'+android_quote(value['content'])+'","createCount":'+str(value['createCount'])+',"lookupCount":'+str(value['lookupCount'])+',"conflictCount":'+str(value['conflictCount'])+'}'
remote_text=android_remote(remote)
checkpoint={'version':2,'session':session,'scenario':'REMOTE_CONFIRMED_BEFORE_LEDGER','applicationId':'com.negi.survey.issue86.it','surveyId':survey,'canonicalPath':canonical,'payloadFingerprint':hashlib.sha256(content.encode()).hexdigest(),'phase1Pid':12345,'processName':'com.negi.survey.issue86.it','terminationMode':'SELF_PROCESS_KILL','checkpointEpochMs':1793577600000,'ready':True,'boundary':'REMOTE_CONFIRMED_BEFORE_LEDGER','readyPid':12345,'remote':remote,'remoteDigest':hashlib.sha256(remote_text.encode()).hexdigest(),'terminationIntent':'RECORDED'}
if case == 'payload': checkpoint['payloadFingerprint']='0'*64
elif case == 'remote_digest': checkpoint['remoteDigest']='0'*64
else: checkpoint['remote']['content']=content.replace('café','tampered')
text=json.dumps(checkpoint,separators=(',',':'),ensure_ascii=False)
python_remote=json.dumps(remote,separators=(',',':'),ensure_ascii=False)
text=text.replace('"remote":'+python_remote,'"remote":'+android_remote(remote),1)
print(text)
PY
  )"
  assert_equals INVALID "$(printf '%s' "$invalid_fixture" | validate_phase1_checkpoint_stream "$session" REMOTE_CONFIRMED_BEFORE_LEDGER)"
done
mkdir "$tmp/no-python"
saved_path="$PATH"
PATH="$tmp/no-python"
parser_status=0
printf '%s' '{}' | validate_phase1_checkpoint_stream "$session" REMOTE_CONFIRMED_BEFORE_LEDGER || parser_status=$?
PATH="$saved_path"
assert_equals 127 "$parser_status"
run_case() {
  : >"$log"
  ISSUE86_FAKE_MODE="$1"
  export ISSUE86_FAKE_MODE
  assert_equals "$2" "$(classify_session_checkpoint "$session")"
}

run_case absent SESSION_ABSENT
grep -Fxq ls "$log"
grep -Fxq -- -1a "$log"
grep -Fxq . "$log"
! grep -Fxq files "$log"
! grep -Fxq -- -ld "$log"
! grep -Fxq sh "$log"
run_case directory_absent SESSION_ABSENT
run_case exists SESSION_EXISTS
run_case checkpoint SESSION_EXISTS
run_case checkpoint_malformed SESSION_CHECK_ERROR
run_case checkpoint_unreadable SESSION_CHECK_ERROR
run_case checkpoint_symlink SESSION_CHECK_ERROR
run_case fake_remote SESSION_EXISTS
run_case staged_a SESSION_EXISTS
run_case staged_b SESSION_EXISTS
run_case staged_both SESSION_EXISTS
run_case inaccessible SESSION_CHECK_ERROR
run_case unavailable SESSION_CHECK_ERROR
run_case missing SESSION_CHECK_ERROR
run_case permission SESSION_CHECK_ERROR
run_case empty SESSION_CHECK_ERROR
run_case malformed SESSION_CHECK_ERROR
run_case transport SESSION_CHECK_ERROR
run_case root_unexpected SESSION_CHECK_ERROR
run_case root_output SESSION_CHECK_ERROR
run_case root_warning SESSION_CHECK_ERROR
run_case root_permission SESSION_CHECK_ERROR
run_case root_transport SESSION_CHECK_ERROR
run_case files_symlink SESSION_CHECK_ERROR
run_case files_other SESSION_CHECK_ERROR
run_case files_special SESSION_CHECK_ERROR
run_case files_bad_output SESSION_CHECK_ERROR
run_case nested_symlink SESSION_CHECK_ERROR
run_case nested_other SESSION_CHECK_ERROR

: >"$log"
ISSUE86_FAKE_MODE=absent
export ISSUE86_FAKE_MODE
assert_equals SESSION_CHECK_ERROR "$(classify_session_checkpoint 'issue86-pd-bad;touch-x')"
[[ ! -s "$log" ]]
assert_equals SESSION_CHECK_ERROR "$(classify_session_checkpoint '')"
[[ ! -s "$log" ]]

# Host-only regression coverage: all evidence and lock paths live beneath this
# test-owned directory, and no ADB helper is called below.
host_evidence_dir="$tmp/host-evidence"
mkdir -m 700 "$host_evidence_dir"
export ISSUE86_HOST_EVIDENCE_DIR="$host_evidence_dir"
export ISSUE86_TEST_LOCK_ROOT="$tmp"
test_host_evidence_dir="$host_evidence_dir"

# Default is the canonical macOS system directory; custom paths may not
# traverse symlinked parents. /tmp itself is tested only when it is an alias.
ISSUE86_HOST_EVIDENCE_DIR=/private/tmp
configure_host_session_paths 'issue86-pd-DefaultPath_1234'
assert_equals /private/tmp "$host_evidence_root"
assert_equals /private/tmp/issue86-pd-DefaultPath_1234.issue86-evidence "$host_evidence_dir"
if [[ -L /tmp && "$(cd -P /tmp && pwd -P)" == /private/tmp ]]; then
  ISSUE86_HOST_EVIDENCE_DIR=/tmp
  assert_fails configure_host_session_paths 'issue86-pd-TmpAlias_1234'
else
  echo 'SKIP: /tmp is not the standard macOS alias to /private/tmp'
fi
mkdir "$tmp/real-parent" "$tmp/real-parent/evidence"
ln -s "$tmp/real-parent" "$tmp/link-parent"
ISSUE86_HOST_EVIDENCE_DIR="$tmp/link-parent/evidence"
assert_fails configure_host_session_paths 'issue86-pd-UnsafeParent_1234'
ISSUE86_HOST_EVIDENCE_DIR="$test_host_evidence_dir"

fresh_session='issue86-pd-HostFresh_1234'
configure_host_session_paths "$fresh_session"
assert_equals HOST_EVIDENCE_UNUSED "$(classify_host_evidence)"
assert_equals LOCK_ABSENT "$(classify_session_lock)"
acquire_session_lock
assert_equals LOCK_ACTIVE "$(classify_session_lock)"
reserve_host_evidence
[[ -f "$phase1_log" && -f "$phase2_log" ]]
python3 - "$host_evidence_dir" "$phase1_log" "$phase2_log" <<'PY'
import os, stat, sys
expected = ((sys.argv[1], 0o700), (sys.argv[2], 0o600), (sys.argv[3], 0o600))
for path, mode in expected:
    actual = stat.S_IMODE(os.lstat(path).st_mode)
    assert actual == mode, (path, oct(actual), oct(mode))
    assert os.path.islink(path) is False, path
PY
printf 'phase-one-preserved\n' >&3
assert_equals HOST_EVIDENCE_EXISTS "$(classify_host_evidence)"
assert_fails reserve_host_evidence
grep -Fxq 'phase-one-preserved' "$phase1_log"
release_session_lock

# A permissive caller umask cannot weaken the evidence directory, logs, or
# runner-created temporary diagnostics.
permission_session='issue86-pd-Umask_1234'
configure_host_session_paths "$permission_session"
umask 022
reserve_host_evidence
private_tmp="$(create_private_temp_file /private/tmp/issue86-permission-test.XXXXXXXX)"
python3 - "$host_evidence_dir" "$phase1_log" "$phase2_log" "$private_tmp" <<'PY'
import os, stat, sys
for path, mode in ((sys.argv[1], 0o700), (sys.argv[2], 0o600), (sys.argv[3], 0o600), (sys.argv[4], 0o600)):
    actual = stat.S_IMODE(os.lstat(path).st_mode)
    assert actual == mode, (path, oct(actual), oct(mode))
    assert not os.path.islink(path), path
PY
rm -- "$private_tmp"
exec 3>&- 4>&-

lock_permission_session='issue86-pd-LockUmask_1234'
configure_host_session_paths "$lock_permission_session"
umask 022
acquire_session_lock
python3 - "$session_lock_dir" "$session_lock_owner" <<'PY'
import os, stat, sys
for path, mode in ((sys.argv[1], 0o700), (sys.argv[2], 0o600)):
    actual = stat.S_IMODE(os.lstat(path).st_mode)
    assert actual == mode, (path, oct(actual), oct(mode))
    assert not os.path.islink(path), path
PY
release_session_lock
umask 077

# Unsafe existing artifacts are rejected without chmod, overwrite, or symlink
# traversal.
unsafe_file_session='issue86-pd-UnsafeFile_1234'
configure_host_session_paths "$unsafe_file_session"
mkdir -m 700 "$host_evidence_dir"
(umask 022; printf 'unsafe evidence remains\n' > "$phase1_log")
assert_equals HOST_EVIDENCE_ERROR "$(classify_host_evidence)"
assert_fails reserve_host_evidence
grep -Fxq 'unsafe evidence remains' "$phase1_log"
python3 - "$phase1_log" <<'PY'
import os, stat, sys
assert stat.S_IMODE(os.lstat(sys.argv[1]).st_mode) == 0o644
PY

unsafe_directory_session='issue86-pd-UnsafeDirectory_1234'
configure_host_session_paths "$unsafe_directory_session"
mkdir -m 755 "$host_evidence_dir"
assert_equals HOST_EVIDENCE_ERROR "$(classify_host_evidence)"
assert_fails reserve_host_evidence
python3 - "$host_evidence_dir" <<'PY'
import os, stat, sys
assert stat.S_IMODE(os.lstat(sys.argv[1]).st_mode) == 0o755
PY

reader_session='issue86-pd-Reader_1234'
configure_host_session_paths "$reader_session"
reserve_host_evidence
printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' >&3
reader_snapshot="$(read_reserved_evidence 1 && printf '.')"
reader_snapshot="${reader_snapshot%.}"
assert_equals ACCEPT_FOR_PHASE_2 "$(classify_phase1_completion "$reader_snapshot" 0 true true)"
exec 3>&- 4>&- 5<&- 6<&-

substitution_session='issue86-pd-Substitution_1234'
configure_host_session_paths "$substitution_session"
reserve_host_evidence
mv "$phase1_log" "$tmp/reserved-original"
printf 'unrelated evidence\n' > "$tmp/unrelated-target"
ln -s "$tmp/unrelated-target" "$phase1_log"
printf 'written to original\n' >&3
grep -Fxq 'written to original' "$tmp/reserved-original"
[[ "$(cat "$tmp/unrelated-target")" == 'unrelated evidence' ]]
# The actual evidence reader must reject both substituted paths before their
# contents can reach the real classifiers or the final output.
assert_fails read_reserved_evidence 1
mv "$phase2_log" "$tmp/reserved-phase2"
ln -s "$tmp/unrelated-target" "$phase2_log"
assert_fails read_reserved_evidence 2
[[ "$(cat "$tmp/unrelated-target")" == 'unrelated evidence' ]]
exec 3>&- 4>&- 5<&- 6<&-

for replacement in regular fifo; do
  configure_host_session_paths "issue86-pd-Replace_${replacement}_1234"
  reserve_host_evidence
  printf 'original evidence\n' >&3
  mv "$phase1_log" "$tmp/original-$replacement"
  if [[ "$replacement" == fifo ]]; then
    mkfifo "$phase1_log"
  else
    printf 'substituted evidence\n' > "$phase1_log"
  fi
  assert_fails read_reserved_evidence 1
  exec 3>&- 4>&-
done
configure_host_session_paths 'issue86-pd-BinaryEvidence_1234'
reserve_host_evidence
printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n\0' >&3
assert_fails read_reserved_evidence 1
exec 3>&- 4>&-
configure_host_session_paths 'issue86-pd-InvalidUtf8_1234'
reserve_host_evidence
printf '\377\n' >&3
assert_fails read_reserved_evidence 1
exec 3>&- 4>&-

uncertain_session='issue86-pd-Uncertain_1234'
configure_host_session_paths "$uncertain_session"
acquire_session_lock
session_operation_uncertain=true
release_session_lock
assert_equals LOCK_ACTIVE "$(classify_session_lock)"
assert_fails acquire_session_lock
session_operation_uncertain=false
release_session_lock

existing_phase1='issue86-pd-HostPhaseOne_1234'
configure_host_session_paths "$existing_phase1"
mkdir -m 700 "$host_evidence_dir"
printf 'existing phase one\n' >"$phase1_log"
assert_equals HOST_EVIDENCE_EXISTS "$(classify_host_evidence)"
assert_fails reserve_host_evidence
grep -Fxq 'existing phase one' "$phase1_log"

existing_phase2='issue86-pd-HostPhaseTwo_1234'
configure_host_session_paths "$existing_phase2"
mkdir -m 700 "$host_evidence_dir"
printf 'existing phase two\n' >"$phase2_log"
assert_equals HOST_EVIDENCE_EXISTS "$(classify_host_evidence)"
assert_fails reserve_host_evidence
grep -Fxq 'existing phase two' "$phase2_log"

symlink_evidence='issue86-pd-HostSymlink_1234'
configure_host_session_paths "$symlink_evidence"
mkdir -m 700 "$host_evidence_dir"
symlink_target="$tmp/symlink-target"
printf 'symlink target preserved\n' >"$symlink_target"
ln -s "$symlink_target" "$phase1_log"
assert_equals HOST_EVIDENCE_ERROR "$(classify_host_evidence)"
assert_fails reserve_host_evidence
grep -Fxq 'symlink target preserved' "$symlink_target"

existing_extra='issue86-pd-HostExtra_1234'
configure_host_session_paths "$existing_extra"
mkdir "$session_lock_dir"
printf 'not-a-pid\n' >"$session_lock_owner"
assert_equals LOCK_ERROR "$(classify_session_lock)"
assert_fails acquire_session_lock

inaccessible_root="$tmp/not-a-directory"
: >"$inaccessible_root"
ISSUE86_HOST_EVIDENCE_DIR="$inaccessible_root"
assert_fails configure_host_session_paths 'issue86-pd-Inaccessible_1234'
inaccessible_dir="$tmp/inaccessible-directory"
mkdir "$inaccessible_dir"
chmod 500 "$inaccessible_dir"
ISSUE86_HOST_EVIDENCE_DIR="$inaccessible_dir"
configure_host_session_paths 'issue86-pd-Inaccessible_5678'
assert_equals HOST_EVIDENCE_ERROR "$(classify_host_evidence)"
chmod 700 "$inaccessible_dir"
ISSUE86_HOST_EVIDENCE_DIR="$test_host_evidence_dir"
export ISSUE86_HOST_EVIDENCE_DIR

lock_session='issue86-pd-LockOwner_1234'
configure_host_session_paths "$lock_session"
acquire_session_lock
assert_fails bash -c '
  source "$1/tools/issue86-process-death-runner.sh"
  configure_host_session_paths "$2"
  acquire_session_lock
' _ "$root" "$lock_session"
ISSUE86_RUNNER_LIB_ONLY=true ISSUE86_HOST_EVIDENCE_DIR="$test_host_evidence_dir" bash -c '
  source "$1/tools/issue86-process-death-runner.sh"
  configure_host_session_paths "$2"
  acquire_session_lock
  install_session_lock_cleanup
' _ "$root" 'issue86-pd-DifferentLock_1234'
other_evidence="$tmp/other-evidence"
mkdir "$other_evidence"
assert_fails env ISSUE86_HOST_EVIDENCE_DIR="$other_evidence" bash -c '
  source "$1/tools/issue86-process-death-runner.sh"
  configure_host_session_paths "$2"
  acquire_session_lock
' _ "$root" "$lock_session"
release_session_lock

auto_release_session='issue86-pd-AutoRelease_1234'
bash -c '
  source "$1/tools/issue86-process-death-runner.sh"
  configure_host_session_paths "$2"
  acquire_session_lock
  install_session_lock_cleanup
' _ "$root" "$auto_release_session"
configure_host_session_paths "$auto_release_session"
assert_equals LOCK_ABSENT "$(classify_session_lock)"
acquire_session_lock
release_session_lock

# Controlled children block on a pipe until the test releases them. The parent
# uses the runner's real lock and EXIT/signal traps, without an Android process.
cat > "$tmp/lock-parent.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
source "$RUNNER_ROOT/tools/issue86-process-death-runner.sh"
configure_host_session_paths "$1"
acquire_session_lock
install_session_lock_cleanup
session_operation_uncertain=true
exec 7<&0
bash -c 'printf "CHILD:%s\n" "$$"; IFS= read -r value <&7; printf "CHILD_DONE\n"; exit "$1"' _ "$2" &
child=$!
status=0
wait "$child" || status=$?
if [[ "$status" == 0 ]]; then session_operation_uncertain=false; fi
exit "$status"
EOF
export RUNNER_ROOT="$root"
perl - "$tmp/lock-parent.sh" <<'PERL'
use strict;
use warnings;
use IPC::Open3;
use Symbol qw(gensym);
$SIG{ALRM} = sub { die "lifecycle test timed out\n" };
alarm 20;
my $parent = shift @ARGV;
for my $mode (qw(normal failure INT TERM abnormal)) {
    my $session = "issue86-pd-Lifecycle_${mode}_1234";
    my $err = gensym;
    my ($in, $out);
    my $pid = open3($in, $out, $err, 'bash', $parent, $session, $mode eq 'failure' ? 7 : 0);
    my $ready = <$out> // die "child did not start ($mode)\n";
    $ready =~ /^CHILD:(\d+)$/ or die "invalid child readiness: $ready";
    my $child = $1;
    my $probe = sub {
        system('bash', '-c', 'source "$RUNNER_ROOT/tools/issue86-process-death-runner.sh"; configure_host_session_paths "$1"; acquire_session_lock; release_session_lock', '_', $session);
        return $? >> 8;
    };
    $probe->() != 0 or die "active child had no lock ($mode)\n";
    if ($mode eq 'INT' || $mode eq 'TERM' || $mode eq 'abnormal') {
        kill($mode eq 'abnormal' ? 'HUP' : $mode, $pid) or die "signal failed\n";
        waitpid($pid, 0) == $pid or die "parent wait failed\n";
        $? != 0 or die "interrupted parent reported success\n";
        kill(0, $child) or die "child did not remain active after parent exit\n";
        $probe->() != 0 or die "live child lost lock ($mode)\n";
        print $in "release\n" or die "child release failed\n";
        close $in;
        my $done = <$out> // die "child did not finish\n";
        $done eq "CHILD_DONE\n" or die "unexpected child result\n";
    } else {
        print $in "release\n" or die "child release failed\n";
        close $in;
        my $done = <$out> // die "child did not finish\n";
        $done eq "CHILD_DONE\n" or die "unexpected child result\n";
        waitpid($pid, 0) == $pid or die "parent wait failed\n";
        my $status = $? >> 8;
        if ($mode eq 'failure') {
            $status == 7 or die "child failure status was $status\n";
            $probe->() != 0 or die "failure did not retain lock\n";
        } else {
            $status == 0 or die "normal parent failed ($status)\n";
            $probe->() == 0 or die "normal completion retained lock\n";
        }
    }
    close $out;
    close $err;
}
alarm 0;
PERL

# A failed EXIT cleanup must not turn a successful body into success.
assert_fails bash -c '
  source "$1/tools/issue86-process-death-runner.sh"
  configure_host_session_paths "issue86-pd-CleanupFailure_1234"
  acquire_session_lock
  install_session_lock_cleanup
  printf "not-the-owner\n" > "$session_lock_owner"
' _ "$root"

# Exact toybox ps header and at least one complete, valid process row are
# required; shell command errors are exercised in the entrypoint tests below.
assert_process_listing() {
  local expected="$1" target_pid="$2" listing="$3"
  assert_equals "$expected" "$(printf '%s' "$listing" | classify_process_listing "$target_pid")"
}
assert_process_listing PRESENT 1234 $'  PID NAME\r\n  1234 com.example\r\n  1 init\r\n'
assert_process_listing ABSENT 12345 $'PID NAME\n123456 other\n1 init\n'
assert_process_listing UNKNOWN 1234 ''
assert_process_listing UNKNOWN 1234 $'  \n\t\n'
assert_process_listing UNKNOWN 1234 $'PID NAME\n'
assert_process_listing UNKNOWN 1234 $'NAME PID\ninit 1\n'
assert_process_listing UNKNOWN 1234 $'PID NAME\nabc com.example\n'
assert_process_listing UNKNOWN 1234 $'PID NAME\n1234\n'
assert_process_listing UNKNOWN 1234 $'PID NAME\n1 init'
assert_process_listing UNKNOWN 1234 $'PID NAME\n1 init\n1 other\n'
assert_process_listing UNKNOWN 0 $'PID NAME\n1 init\n'

valid_session_id 'issue86-pd-ValidHost_1234'
assert_fails valid_session_id 'issue86-pd-bad;touch-x'

phase1_log="$tmp/phase1.log"
valid_phase1_output=$'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n'
run_phase1_case() {
  printf %s "$1" >"$phase1_log"
  assert_equals "$5" "$(classify_phase1_completion "$1" "$2" "$3" "$4")"
}

printf -v large_text '%0100000d' 0
run_phase1_case "$large_text"$'\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0' 0 true true ACCEPT_FOR_PHASE_2
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0' 0 true true ACCEPT_FOR_PHASE_2
run_phase1_case '' 0 true true REJECT
run_phase1_case 'INSTRUMENTATION_CODE: 0' 0 true true REJECT
run_phase1_case "$valid_phase1_output"$'\n\n' 0 true true ACCEPT_FOR_PHASE_2
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\r\nINSTRUMENTATION_CODE: 0\r\n' 0 true true REJECT
run_phase1_case "$valid_phase1_output" 0 true true ACCEPT_FOR_PHASE_2
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=All tests passed.\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
# Missing checkpoint and wrong session/boundary both prevent the verified checkpoint gate.
run_phase1_case "$valid_phase1_output" 0 false true REJECT
run_phase1_case "$valid_phase1_output" 0 false true REJECT
run_phase1_case "$valid_phase1_output" 0 true false REJECT
run_phase1_case $'InvalidTestClassError\nINSTRUMENTATION_CODE: -1\n' 1 true true REJECT
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 1\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=unexpected\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case "$valid_phase1_output" 1 true true REJECT
run_phase1_case $'INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case $'java.lang.AssertionError: failed\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_RESULT: error=oops\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_STATUS_CODE: garbage\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' 0 true true REJECT
run_phase1_case $'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n' 0 true true ACCEPT_FOR_PHASE_2

phase2_log="$tmp/phase2.log"
phase2_method=recoverRemoteConfirmedBeforeLedger
phase2_class=com.negi.survey.issue86.Issue86ProcessDeathPhase2Test
phase2_pass="$(printf 'INSTRUMENTATION_STATUS: class=%s\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=%s\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n' "$phase2_class" "$phase2_method" "$phase2_class" "$phase2_method")"
run_phase2_case() {
  printf '%s\n' "$1" > "$phase2_log"
  assert_equals "$3" "$(classify_phase2_completion "$1" "$2" "$phase2_method")"
}
# Exercise the same descriptor snapshot, classifier, and byte-preserving output
# sequence used after Phase 2, then verify a failed result is rejected.
configure_host_session_paths 'issue86-pd-Phase2Reader_1234'
reserve_host_evidence
printf '%s\n' "$phase2_pass" >&4
phase2_snapshot="$(read_reserved_evidence 2 && printf '.')"
phase2_snapshot="${phase2_snapshot%.}"
assert_equals ACCEPT "$(classify_phase2_completion "$phase2_snapshot" 0 "$phase2_method")"
assert_equals REJECT "$(classify_phase2_completion "$phase2_snapshot" 7 "$phase2_method")"
assert_equals "$phase2_pass" "$(printf '%s' "$phase2_snapshot")"
exec 3>&- 4>&- 5<&- 6<&-

run_phase2_case "$phase2_pass" 0 ACCEPT
run_phase2_case "${phase2_pass/INSTRUMENTATION_STATUS_CODE: 0/INSTRUMENTATION_STATUS_CODE: -2}" 0 REJECT
run_phase2_case "${phase2_pass/INSTRUMENTATION_STATUS_CODE: 0/INSTRUMENTATION_STATUS_CODE: -3}" 0 REJECT
run_phase2_case "${phase2_pass/INSTRUMENTATION_STATUS_CODE: 0/INSTRUMENTATION_STATUS_CODE: -4}" 0 REJECT
run_phase2_case "${phase2_pass/numtests=1/numtests=0}" 0 REJECT
run_phase2_case "${phase2_pass/numtests=1/numtests=2}" 0 REJECT
run_phase2_case "${phase2_pass/OK (1 test)/FAILURES!!!}" 0 REJECT
run_phase2_case "${phase2_pass/OK (1 test)/}" 0 REJECT
run_phase2_case "${phase2_pass/INSTRUMENTATION_CODE: -1/}" 0 REJECT
run_phase2_case "$phase2_pass"$'\nINSTRUMENTATION_CODE: -1' 0 REJECT
run_phase2_case "${phase2_pass/INSTRUMENTATION_CODE: -1/INSTRUMENTATION_CODE: 0}" 0 REJECT
run_phase2_case "$phase2_pass" 1 REJECT
run_phase2_case $'INSTRUMENTATION_FAILED: transport error' 0 REJECT
run_phase2_case "${phase2_pass/$phase2_method/otherTest}" 0 REJECT

# Run the unmodified production entrypoint with a PATH-scoped fake adb.
flow_dir="$tmp/full-flow"
mkdir -p "$flow_dir/bin"
cat > "$flow_dir/bin/adb" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FLOW_DIR/commands"
[[ "$1" == -s && "$2" == R5GL2118ZGD ]] || exit 91
shift 2
case "$*" in
  'get-state') printf 'device\n' ;;
  'shell getprop ro.product.model') printf 'SM-S731U\n' ;;
  'shell pm path com.negi.survey.issue86.it'|'shell pm path com.negi.survey.issue86.it.test') printf 'package:/mock.apk\n' ;;
  'shell cmd package list instrumentation') printf 'instrumentation:com.negi.survey.issue86.it.test/androidx.test.runner.AndroidJUnitRunner (target=com.negi.survey.issue86.it)\n' ;;
  'shell run-as com.negi.survey.issue86.it id') printf 'uid=10362(u0_a362)\n' ;;
  'shell run-as com.negi.survey.issue86.it ls -1a .')
    if [[ -f "$FLOW_DIR/phase1-started" ]]; then printf '.\n..\nfiles\n'; else printf '.\n..\n'; fi ;;
  'shell run-as com.negi.survey.issue86.it ls -ld files') printf '%s\n' 'drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files' ;;
  'shell run-as com.negi.survey.issue86.it ls -1a files') printf '.\n..\nissue86_process_death\n' ;;
  'shell run-as com.negi.survey.issue86.it ls -ld files/issue86_process_death') printf '%s\n' 'drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files/issue86_process_death' ;;
  'shell run-as com.negi.survey.issue86.it ls -1a files/issue86_process_death') printf '.\n..\n%s\n' "$FLOW_SESSION" ;;
  "shell run-as com.negi.survey.issue86.it ls -ld files/issue86_process_death/$FLOW_SESSION") printf '%s\n' "drwx------ 2 u0_a362 u0_a362 4096 2026-11-03 10:00 files/issue86_process_death/$FLOW_SESSION" ;;
  "shell run-as com.negi.survey.issue86.it ls -1a files/issue86_process_death/$FLOW_SESSION") printf '.\n..\ncheckpoint-v1.json\n' ;;
  "shell run-as com.negi.survey.issue86.it ls -ld files/issue86_process_death/$FLOW_SESSION/checkpoint-v1.json") printf '%s\n' "-rw------- 1 u0_a362 u0_a362 256 2026-11-03 10:00 files/issue86_process_death/$FLOW_SESSION/checkpoint-v1.json" ;;
  'shell run-as com.negi.survey.issue86.it cat '*)
    if [[ "$FLOW_MODE" != paused ]]; then IFS= read -r token < "$FLOW_DIR/checkpoint-fifo"; fi
    if [[ "$FLOW_MODE" == checkpoint_cat_failure ]]; then echo 'error: device offline' >&2; exit 1; fi
    python3 - "$FLOW_SESSION" "$FLOW_MODE" <<'PYCHECKPOINT'
import hashlib, json, sys
session, mode = sys.argv[1:]
survey = 'issue86-process-death-' + session
canonical = 'exports/surveys/' + hashlib.sha256(survey.lower().encode()).hexdigest() + '.json'
content = json.dumps({'survey_id':'synthetic','note':'café / \\ "line"'},separators=(',',':'),ensure_ascii=False)
remote = {
    'version': 1,
    'path': canonical,
    'content': content,
    'createCount': 1,
    'lookupCount': 0,
    'conflictCount': 0,
}
def android_quote(value):
    return json.dumps(value, ensure_ascii=False)[1:-1].replace('/', r'\/')
def android_remote(value):
    fields=[]
    for key, item in value.items():
        if isinstance(item, str): encoded='"' + android_quote(item) + '"'
        elif isinstance(item, bool): encoded='true' if item else 'false'
        elif item is None: encoded='null'
        elif isinstance(item, (int, float)): encoded=json.dumps(item, separators=(',', ':'))
        else: raise TypeError('unsupported fixture JSON value')
        fields.append('"' + android_quote(key) + '":' + encoded)
    return '{' + ','.join(fields) + '}'
remote_text = android_remote(remote)
checkpoint = {
    'version': 2,
    'session': session,
    'scenario': 'REMOTE_CONFIRMED_BEFORE_LEDGER',
    'applicationId': 'com.negi.survey.issue86.it',
    'surveyId': survey,
    'canonicalPath': canonical,
    'payloadFingerprint': hashlib.sha256(content.encode('utf-8')).hexdigest(),
    'phase1Pid': 12345,
    'processName': 'com.negi.survey.issue86.it',
    'terminationMode': 'SELF_PROCESS_KILL',
    'checkpointEpochMs': 1793577600000,
    'ready': True,
    'boundary': 'REMOTE_CONFIRMED_BEFORE_LEDGER',
    'readyPid': 12345,
    'remote': remote,
    'remoteDigest': hashlib.sha256(remote_text.encode('utf-8')).hexdigest(),
    'terminationIntent': 'RECORDED',
}
variants = {
    'checkpoint_truncated': lambda value: None,
    'checkpoint_missing': lambda value: value.pop('phase1Pid'),
    'checkpoint_pid_string': lambda value: value.update(phase1Pid='12345'),
    'checkpoint_pid_float': lambda value: value.update(readyPid=12345.0),
    'checkpoint_pid_zero': lambda value: value.update(readyPid=0),
    'checkpoint_pid_negative': lambda value: value.update(readyPid=-1),
    'checkpoint_pid_too_large': lambda value: value.update(readyPid=2147483648),
    'checkpoint_process_missing': lambda value: value.pop('processName'),
    'checkpoint_state_type': lambda value: value.update(ready='true'),
    'checkpoint_not_ready': lambda value: value.update(ready=False),
    'checkpoint_pid_mismatch': lambda value: value.update(readyPid=12346),
    'checkpoint_wrong_identity': lambda value: value.update(processName='com.negi.survey'),
    'checkpoint_wrong_package': lambda value: value.update(applicationId='com.negi.survey'),
    'checkpoint_wrong_session': lambda value: value.update(session='issue86-pd-WrongSess_1234'),
    'checkpoint_wrong_scenario': lambda value: value.update(scenario='LEDGER_COMMITTED_BEFORE_CLEANUP'),
    'checkpoint_wrong_boundary': lambda value: value.update(boundary='LEDGER_COMMITTED_BEFORE_CLEANUP'),
    'checkpoint_wrong_termination': lambda value: value.update(terminationMode='SIGKILL'),
    'checkpoint_wrong_intent': lambda value: value.update(terminationIntent='NOT_RECORDED'),
    'checkpoint_payload_fingerprint_bad': lambda value: value.update(payloadFingerprint='0' * 64),
    'checkpoint_remote_digest_bad': lambda value: value.update(remoteDigest='0' * 64),
    'checkpoint_remote_content_tampered': lambda value: value['remote'].update(content=value['remote']['content'].replace('café', 'tampered')),
}
variant = variants.get(mode)
if variant:
    variant(checkpoint)
if mode == 'checkpoint_top_type':
    sys.stdout.write('[]')
    raise SystemExit(0)
if mode == 'success':
    checkpoint['futureOptionalField'] = {'preserved': True}
    checkpoint['remote']['futureRemoteField'] = 1
    checkpoint['remoteDigest'] = hashlib.sha256(android_remote(remote).encode('utf-8')).hexdigest()
text = json.dumps(checkpoint, separators=(',', ':'), ensure_ascii=False)
python_remote = json.dumps(remote, separators=(',', ':'), ensure_ascii=False)
text = text.replace('"remote":' + python_remote, '"remote":' + android_remote(remote), 1)
if mode == 'checkpoint_truncated':
    text = text[:-1]
elif mode == 'checkpoint_duplicate':
    text = text.replace('"readyPid":12345,', '"readyPid":12345,"readyPid":12346,', 1)
sys.stdout.write(text)
PYCHECKPOINT
    ;;
  'shell ps -A -o PID,NAME')
    case "$FLOW_MODE" in
      success)
        if [[ ! -e "$FLOW_DIR/ps-present-once" ]]; then
          : > "$FLOW_DIR/ps-present-once"
          printf 'PID NAME\n12345 com.negi.survey.issue86.it\n1 init\n'
        else
          printf 'PID NAME\n123456 other\n1 init\n'
        fi ;;
      ps_empty) : ;;
      ps_whitespace) printf '   \n\t\n' ;;
      ps_header) printf 'PID NAME\n' ;;
      ps_badheader) printf 'NAME PID\ninit 1\n' ;;
      ps_badpid) printf 'PID NAME\nabc init\n' ;;
      ps_truncated) printf 'PID NAME\n1 init' ;;
      ps_adb_failure) printf 'PID NAME\n1 init\n'; printf 'adb: device offline\n' >&2; exit 1 ;;
      ps_command_failure) printf 'ps: invalid option\n' >&2; exit 1 ;;
      ps_diagnostic) printf 'PID NAME\n1 init\n'; printf 'ps: incomplete listing\n' >&2 ;;
      ps_present) printf 'PID NAME\n1 init\n12345 com.negi.survey.issue86.it\n' ;;
      *) printf 'PID NAME\n1 init\n123456 other\n' ;;
    esac ;;

  *Issue86ProcessDeathPhase1Test*)
    : > "$FLOW_DIR/phase1-started"
    if [[ "$FLOW_MODE" == paused ]]; then
      printf "%s\n" "$$" > "$FLOW_DIR/child-pid"
      IFS= read -r token < "$FLOW_DIR/release-fifo"
    fi
    if [[ "$FLOW_MODE" == phase1_failure ]]; then
      printf 'INSTRUMENTATION_FAILED: mock failure\n'
    else
      printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n'
    fi
    if [[ "$FLOW_MODE" == substitute ]]; then
      mv "$FLOW_EVIDENCE_DIR/phase1.log" "$FLOW_DIR/original-phase1"
      ln -s "$FLOW_DIR/target" "$FLOW_EVIDENCE_DIR/phase1.log"
    fi
    if [[ "$FLOW_MODE" != paused ]]; then printf 'ready\n' > "$FLOW_DIR/checkpoint-fifo"; fi ;;
  *Issue86ProcessDeathPhase2Test*)
    if [[ "$FLOW_MODE" == phase2_failure ]]; then
      printf 'INSTRUMENTATION_FAILED: mock failure\n'; exit 7
    fi
    for code in 1 0; do
      printf 'INSTRUMENTATION_STATUS: class=com.negi.survey.issue86.Issue86ProcessDeathPhase2Test\n'
      printf 'INSTRUMENTATION_STATUS: numtests=1\n'
      printf 'INSTRUMENTATION_STATUS: test=recoverRemoteConfirmedBeforeLedger\n'
      printf 'INSTRUMENTATION_STATUS_CODE: %s\n' "$code"
    done
    printf 'INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n' ;;
  *) printf 'unexpected mock adb command: %s\n' "$*" >&2; exit 92 ;;
esac
MOCK
cat > "$flow_dir/bin/sleep" <<'MOCKSLEEP'
#!/usr/bin/env bash
if [[ "${FLOW_MODE:-}" == ps_present || "${FLOW_MODE:-}" == success ]]; then exit 0; fi
exec /bin/sleep "$@"
MOCKSLEEP
chmod 700 "$flow_dir/bin/sleep"
chmod 700 "$flow_dir/bin/adb"
export FLOW_BASE="$flow_dir" FLOW_RUNNER="$root/tools/issue86-process-death-runner.sh"
python3 - <<'PYFLOW'
import os, pathlib, subprocess, signal
base=pathlib.Path(os.environ['FLOW_BASE'])
runner=os.environ['FLOW_RUNNER']
invalid_checkpoint_modes = {
    'checkpoint_truncated','checkpoint_missing','checkpoint_pid_string','checkpoint_pid_float',
    'checkpoint_pid_zero','checkpoint_pid_negative','checkpoint_pid_too_large',
    'checkpoint_process_missing','checkpoint_state_type','checkpoint_not_ready','checkpoint_pid_mismatch',
    'checkpoint_wrong_identity','checkpoint_wrong_package','checkpoint_wrong_session',
    'checkpoint_wrong_scenario','checkpoint_wrong_boundary','checkpoint_wrong_termination','checkpoint_wrong_intent',
    'checkpoint_payload_fingerprint_bad','checkpoint_remote_digest_bad','checkpoint_remote_content_tampered',
    'checkpoint_duplicate','checkpoint_top_type',
}
for mode in ('success','phase1_failure','phase2_failure','substitute','ps_empty','ps_whitespace','ps_header','ps_badheader','ps_badpid','ps_truncated','ps_adb_failure','ps_command_failure','ps_diagnostic','ps_present','checkpoint_cat_failure', *sorted(invalid_checkpoint_modes)):
    directory=base/mode
    directory.mkdir()
    os.mkfifo(directory/'checkpoint-fifo')
    (directory/'target').write_text('external target remains intact\n')
    session='issue86-pd-'+base.parent.name+'_'+mode
    evidence_dir=pathlib.Path('/private/tmp')/(session+'.issue86-evidence')
    evidence=[evidence_dir/('phase'+str(i)+'.log') for i in (1,2)]
    lock=pathlib.Path('/private/tmp')/('.'+session+'.issue86-process-death.lock')
    assert not evidence_dir.exists() and not evidence_dir.is_symlink()
    assert all(not p.exists() and not p.is_symlink() for p in evidence+[lock])
    env=os.environ.copy()
    for key in ('ISSUE86_RUNNER_LIB_ONLY','ISSUE86_HOST_EVIDENCE_DIR','ISSUE86_TEST_LOCK_ROOT'):
        env.pop(key,None)
    env.update(FLOW_DIR=str(directory),FLOW_EVIDENCE_DIR=str(evidence_dir),FLOW_MODE=mode,FLOW_SESSION=session,PATH=str(base/'bin')+os.pathsep+env['PATH'])
    p=subprocess.Popen(['bash',runner,session,'REMOTE_CONFIRMED_BEFORE_LEDGER','--execute'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,start_new_session=True)
    try:
        out,err=p.communicate(timeout=15)
        commands=(directory/'commands').read_text()
        assert 'Issue86ProcessDeathPhase1Test' in commands,(mode,commands,err)
        if mode=='success':
            assert p.returncode==0 and b'OK (1 test)' in out and not lock.exists(),(mode,p.returncode,out,err)
            assert 'Issue86ProcessDeathPhase2Test' in commands
            assert sum(line.endswith('shell ps -A -o PID,NAME') for line in commands.splitlines())==2,commands
        else:
            assert p.returncode!=0 and lock.is_dir(),(mode,p.returncode,out,err)
            phase1_calls=[line for line in commands.splitlines() if 'shell am instrument' in line and 'Issue86ProcessDeathPhase1Test' in line]
            phase2_calls=[line for line in commands.splitlines() if 'shell am instrument' in line and 'Issue86ProcessDeathPhase2Test' in line]
            assert len(phase1_calls)==1,(mode,phase1_calls)
            if mode in ('phase1_failure','substitute','checkpoint_cat_failure') or mode.startswith('ps_') or mode in invalid_checkpoint_modes:
                assert 'Issue86ProcessDeathPhase2Test' not in commands
                assert len(phase2_calls)==0,(mode,phase2_calls)
            else:
                assert 'Issue86ProcessDeathPhase2Test' in commands
                assert len(phase2_calls)==1,(mode,phase2_calls)
            if mode in invalid_checkpoint_modes:
                assert b'Phase 1 checkpoint is malformed or belongs to another session' in err,(mode,err)
                assert 'shell ps -A -o PID,NAME' not in commands,(mode,commands)
                assert b'OK (1 test)' not in out and b'PASS' not in out,(mode,out)
                assert evidence[0].stat().st_size>0 and evidence[1].stat().st_size==0,(mode,evidence)
                owner=(lock/'owner-pid').read_text().strip()
                assert owner.isdigit() and int(owner)>0,(mode,owner)
            if mode=='checkpoint_cat_failure':
                assert b'could not inspect Phase 1 checkpoint' in err,(mode,err)
                assert 'shell ps -A -o PID,NAME' not in commands,(mode,commands)
                assert b'OK (1 test)' not in out,(mode,out)
            if mode.startswith('ps_'):
                expected = b'Phase 1 target process did not disappear' if mode == 'ps_present' else b'could not verify Phase 1 process listing'
                assert expected in err,(mode,err)
                assert b'OK (1 test)' not in out
        assert (directory/'target').read_text()=='external target remains intact\n'
        assert all(path.exists() or path.is_symlink() for path in evidence)
        import stat
        assert stat.S_IMODE(evidence_dir.lstat().st_mode)==0o700
        if mode=='substitute':
            assert evidence[0].is_symlink()
            assert stat.S_IMODE(evidence[1].lstat().st_mode)==0o600 and not evidence[1].is_symlink()
        else:
            assert all(stat.S_IMODE(path.lstat().st_mode)==0o600 and not path.is_symlink() for path in evidence)
        print('full-flow',mode,'PASS')
    finally:
        try: os.killpg(p.pid,signal.SIGKILL)
        except ProcessLookupError: pass
        if p.poll() is None: p.communicate()
        for path in evidence:
            if path.exists() or path.is_symlink(): path.unlink()
        if evidence_dir.exists(): evidence_dir.rmdir()
        if lock.is_dir():
            (lock/'owner-pid').unlink()
            lock.rmdir()
# Pause the real Phase 1 child, check same-session contention, and terminate
# the actual runner while its mock instrumentation is still active.
import time
mode='paused'
directory=base/mode
directory.mkdir()
os.mkfifo(directory/'release-fifo')
session='issue86-pd-'+base.parent.name+'_paused'
evidence_dir=pathlib.Path('/private/tmp')/(session+'.issue86-evidence')
evidence=[evidence_dir/('phase'+str(i)+'.log') for i in (1,2)]
lock=pathlib.Path('/private/tmp')/('.'+session+'.issue86-process-death.lock')
assert not evidence_dir.exists() and not evidence_dir.is_symlink()
assert all(not p.exists() and not p.is_symlink() for p in evidence+[lock])
env=os.environ.copy()
for key in ('ISSUE86_RUNNER_LIB_ONLY','ISSUE86_HOST_EVIDENCE_DIR','ISSUE86_TEST_LOCK_ROOT'):
    env.pop(key,None)
env.update(FLOW_DIR=str(directory),FLOW_EVIDENCE_DIR=str(evidence_dir),FLOW_MODE=mode,FLOW_SESSION=session,PATH=str(base/'bin')+os.pathsep+env['PATH'])
args=['bash',runner,session,'REMOTE_CONFIRMED_BEFORE_LEDGER','--execute']
p=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,start_new_session=True)
try:
    deadline=time.monotonic()+10
    while not (directory/'child-pid').exists():
        if p.poll() is not None or time.monotonic()>deadline: raise RuntimeError('phase 1 readiness failed')
        time.sleep(.01)
    child=int((directory/'child-pid').read_text())
    contender=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=10)
    assert contender.returncode!=0 and b'lock is unavailable' in contender.stderr
    os.kill(p.pid,signal.SIGTERM)
    out,err=p.communicate(timeout=10)
    assert p.returncode!=0 and lock.is_dir() and b'OK (1 test)' not in out
    os.kill(child,0)
    contender=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=10)
    assert contender.returncode!=0 and b'lock is unavailable' in contender.stderr
    print('full-flow interruption and contention PASS')
finally:
    # Only this test-owned process group and the unique files above are touched.
    try: os.killpg(p.pid,signal.SIGKILL)
    except ProcessLookupError: pass
    if p.poll() is None: p.communicate()
    for path in evidence:
        if path.exists() or path.is_symlink(): path.unlink()
    if evidence_dir.exists(): evidence_dir.rmdir()
    if lock.is_dir():
        (lock/'owner-pid').unlink()
        lock.rmdir()
PYFLOW

echo 'issue86 process-death runner mock regression tests: PASS'
