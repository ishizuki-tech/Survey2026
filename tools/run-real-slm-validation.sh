#!/usr/bin/env bash
# Runs the Issue #81 real-model fixtures against a local, side-by-side debug build.
set -euo pipefail

APP_PACKAGE="com.negi.survey.local"
TEST_CLASS="com.negi.survey.screens.RealLiteRtAiFollowupFlowInstrumentationTest"
QUESTIONS=(Q7 Q8 Q9 Q10 Q11 Q12 Q13 Q14 Q15 Q16)
ITERATIONS=1
SERIAL=""

usage() {
  cat <<'EOF'
Usage: ./tools/run-real-slm-validation.sh [--serial SERIAL] [--iterations N]

Builds a local debug package with a versionCode one greater than the installed
com.negi.survey.local package, runs the Issue #81 Q7-Q16 instrumentation
fixtures, and copies the newest NDJSON result into build/real-model-validation/.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    --iterations)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      ITERATIONS="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]] || { echo "--iterations must be a positive integer" >&2; exit 2; }

if [[ -z "$SERIAL" ]]; then
  DEVICES=()
  while IFS= read -r device; do
    [[ -n "$device" ]] && DEVICES+=("$device")
  done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [[ ${#DEVICES[@]} -ne 1 ]]; then
    echo "Specify --serial when zero or multiple Android devices are connected." >&2
    exit 2
  fi
  SERIAL="${DEVICES[0]}"
fi

adb -s "$SERIAL" get-state >/dev/null

MANUFACTURER="$(adb -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d '\r')"
MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
API_LEVEL="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
INSTALLED_VERSION_CODE="$({ adb -s "$SERIAL" shell dumpsys package "$APP_PACKAGE" || true; } | tr -d '\r' | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)"
INSTALLED_VERSION_CODE="${INSTALLED_VERSION_CODE:-0}"
[[ "$INSTALLED_VERSION_CODE" =~ ^[0-9]+$ ]] || { echo "Could not parse installed versionCode" >&2; exit 1; }

VALIDATION_VERSION_CODE=$((INSTALLED_VERSION_CODE + 1))
[[ "$VALIDATION_VERSION_CODE" -le 2147483647 ]] || { echo "No safe higher Android versionCode is available" >&2; exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="build/real-model-validation/$STAMP"
mkdir -p "$RESULT_DIR"

cat > "$RESULT_DIR/metadata.txt" <<EOF
serial=$SERIAL
manufacturer=$MANUFACTURER
model=$MODEL
android_version=$ANDROID_VERSION
api_level=$API_LEVEL
package=$APP_PACKAGE
installed_version_code=$INSTALLED_VERSION_CODE
validation_version_code=$VALIDATION_VERSION_CODE
iterations=$ITERATIONS
EOF

echo "Real SLM validation"
echo "Device: $MANUFACTURER $MODEL"
echo "Serial: $SERIAL"
echo "Android: $ANDROID_VERSION (API $API_LEVEL)"
echo "Installed $APP_PACKAGE versionCode: $INSTALLED_VERSION_CODE"
echo "Validation build versionCode: $VALIDATION_VERSION_CODE"
echo "Iterations: $ITERATIONS"

set +e
ANDROID_SERIAL="$SERIAL" ./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -PlocalBuild=true \
  -Papp.versionCode="$VALIDATION_VERSION_CODE" \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.ITERATIONS="$ITERATIONS" \
  2>&1 | tee "$RESULT_DIR/gradle.log"
GRADLE_STATUS=${PIPESTATUS[0]}
set -e

NDJSON_PATH="$(adb -s "$SERIAL" shell run-as "$APP_PACKAGE" sh -c 'ls -1t files/real_model_test_results/*.ndjson 2>/dev/null | head -n 1' 2>/dev/null | tr -d '\r')"
if [[ -n "$NDJSON_PATH" ]]; then
  adb -s "$SERIAL" shell run-as "$APP_PACKAGE" cat "$NDJSON_PATH" > "$RESULT_DIR/results.ndjson"
  RESULT_SOURCE="app-private NDJSON"
else
  LOGCAT_REPORT="$(rg -l 'REAL_AI_FIXTURE_NDJSON' app/build/outputs/androidTest-results/connected/debug 2>/dev/null | tail -n 1 || true)"
  if [[ -n "$LOGCAT_REPORT" ]]; then
    cp "$LOGCAT_REPORT" "$RESULT_DIR/instrumentation-logcat.txt"
    sed -n 's/.*REAL_AI_FIXTURE_NDJSON //p' "$LOGCAT_REPORT" > "$RESULT_DIR/results.ndjson"
    RESULT_SOURCE="instrumentation log fallback"
  fi
fi

if [[ ! -s "$RESULT_DIR/results.ndjson" ]]; then
  echo "No Issue #81 structured result was found in app-private storage or instrumentation output." >&2
  exit 1
fi

echo
echo "Per-question fixture summary"
printf '%-5s %-5s %-10s %-12s %-10s %-14s %-8s %-10s\n' \
  "Question" "Runs" "ValidEval" "MissingMatch" "Followup" "SemanticReview" "Failures" "AvgMs"
for NODE in "${QUESTIONS[@]}"; do
  RUNS="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node)] | length' "$RESULT_DIR/results.ndjson")"
  VALID_EVAL="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node and .productionParseSuccess == true and .productionPolicyDecision != "FAILURE")] | length' "$RESULT_DIR/results.ndjson")"
  DETERMINISTIC_COUNT="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node and .deterministicExpectationMatch != null)] | length' "$RESULT_DIR/results.ndjson")"
  if [[ "$DETERMINISTIC_COUNT" -eq 0 ]]; then
    MISSING_MATCH="n/a"
  else
    MATCHED="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node and .deterministicExpectationMatch == true)] | length' "$RESULT_DIR/results.ndjson")"
    MISSING_MATCH="$MATCHED/$DETERMINISTIC_COUNT"
  fi
  FOLLOWUP="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node and .followupAccepted == true)] | length' "$RESULT_DIR/results.ndjson")"
  SEMANTIC_REVIEW="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node and .classification == "SEMANTIC_REVIEW")] | length' "$RESULT_DIR/results.ndjson")"
  FAILURES="$(jq -s --arg node "$NODE" '[.[] | select(.nodeId == $node and (.classification == "TIMEOUT" or .classification == "RUNTIME_ERROR"))] | length' "$RESULT_DIR/results.ndjson")"
  AVG_MS="$(jq -sr --arg node "$NODE" '[.[] | select(.nodeId == $node) | .overallElapsedMs] | if length == 0 then "n/a" else ((add / length) | floor | tostring) end' "$RESULT_DIR/results.ndjson")"
  printf '%-5s %-5s %-10s %-12s %-10s %-14s %-8s %-10s\n' \
    "$NODE" "$RUNS" "$VALID_EVAL/$RUNS" "$MISSING_MATCH" "$FOLLOWUP/$RUNS" "$SEMANTIC_REVIEW" "$FAILURES" "$AVG_MS"
done
echo
echo "Classification counts"
jq -r '.classification' "$RESULT_DIR/results.ndjson" | sort | uniq -c || true
echo "Result source: $RESULT_SOURCE"
echo "Results: $RESULT_DIR"

if [[ "$GRADLE_STATUS" -ne 0 ]]; then
  echo "Instrumentation infrastructure failed; Gradle exit=$GRADLE_STATUS" >&2
  exit "$GRADLE_STATUS"
fi
