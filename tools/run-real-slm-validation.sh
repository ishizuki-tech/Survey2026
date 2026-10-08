#!/usr/bin/env bash
# Runs the Issue #92 categorized real-model fixtures against a local, side-by-side debug build.
set -euo pipefail

APP_PACKAGE="com.negi.survey.local"
TEST_CLASS="com.negi.survey.screens.RealLiteRtAiFollowupFlowInstrumentationTest"
TEST_METHOD="${TEST_CLASS}#q7_q16_real_model_fixture_runner"
QUESTIONS=(Q7 Q8 Q9 Q10 Q11 Q12 Q13 Q14 Q15 Q16)
CATEGORIES=(COMPLETE PARTIAL UNHELPFUL)
ITERATIONS=1
SERIAL=""

usage() {
  cat <<'EOF'
Usage: ./tools/run-real-slm-validation.sh [--serial SERIAL] [--iterations N]

Builds a local debug package with a versionCode one greater than the installed
com.negi.survey.local package, runs the Issue #92 categorized Q7-Q16 instrumentation
fixtures, and copies that run's NDJSON and CSV results into
build/real-model-validation/.
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

EXPECTED_RECORD_COUNT=$(( ${#QUESTIONS[@]} * ${#CATEGORIES[@]} * ITERATIONS ))
QUESTION_LIST_JSON="$(printf '%s\n' "${QUESTIONS[@]}" | jq -R . | jq -sc .)"
CATEGORY_LIST_JSON="$(printf '%s\n' "${CATEGORIES[@]}" | jq -R . | jq -sc .)"

# A candidate must contain the complete Q7-Q16 fixture matrix for this run.
# Keeping this as the single acceptance predicate makes the direct app-private
# file and reconstructed structured-logcat artifact subject to the same checks.
artifact_validation_report() {
  jq -s \
    --arg run_id "$RESULT_RUN_ID" \
    --argjson expected_count "$EXPECTED_RECORD_COUNT" \
    --argjson iterations "$ITERATIONS" \
    --argjson questions "$QUESTION_LIST_JSON" \
    --argjson categories "$CATEGORY_LIST_JSON" '
      def pair: "\(.category)\u0000\(.nodeId)\u0000\(.iteration)";
      def expected_pairs:
        [ $categories[] as $category
          | $questions[] as $node
          | range(1; $iterations + 1) as $iteration
          | "\($category)\u0000\($node)\u0000\($iteration)"
        ];
      . as $records
      | [ $records[] | select(type != "object") ] as $non_objects
      | [ $records[]
          | select(type == "object")
          | select(
              .runId != $run_id or
              (.fixtureId | type != "string") or
              (.category | type != "string") or
              (.questionText | type != "string") or
              (.initialAnswer | type != "string") or
              (.rawEval | type != "string") or
              ((.nodeId | type) != "string") or
              ((.iteration | type) != "number") or
              (.iteration as $iteration | ($iteration | floor) != $iteration) or
              (.iteration < 1 or .iteration > $iterations) or
              (.nodeId as $node | ($questions | index($node)) == null) or
              (.category as $category | ($categories | index($category)) == null)
            )
        ] as $invalid_records
      | [ $records[] | select(type == "object") | pair ] as $actual_pairs
      | expected_pairs as $expected_pairs
      | [ $expected_pairs[] as $pair
          | select(($actual_pairs | index($pair)) == null)
          | $pair
        ] as $missing_pairs
      | [ $actual_pairs | group_by(.)[]
          | select(length > 1)
          | { pair: .[0], count: length }
        ] as $duplicate_pairs
      | [ $actual_pairs[] as $pair
          | select(($expected_pairs | index($pair)) == null)
          | $pair
        ] as $unexpected_pairs
      | {
          expectedRecordCount: $expected_count,
          actualRecordCount: ($records | length),
          missingPairs: $missing_pairs,
          duplicatePairs: $duplicate_pairs,
          unexpectedPairs: $unexpected_pairs,
          invalidRecordCount: (($non_objects | length) + ($invalid_records | length))
        }
      | .valid = (
          .actualRecordCount == .expectedRecordCount and
          .missingPairs == [] and
          .duplicatePairs == [] and
          .unexpectedPairs == [] and
          .invalidRecordCount == 0
        )
    ' "$1"
}

is_valid_result() {
  local candidate="$1"
  local diagnostics="$RESULT_DIR/$(basename "$candidate").jq-validation.stderr"

  # `adb shell run-as` can print a textual package error while returning zero.
  # This is an intentionally probed, non-authoritative candidate: retain jq's
  # diagnostic for inspection and fall through to the structured-logcat source
  # instead of emitting an unexplained parser error during a successful run.
  if ! jq -e -s 'all(type == "object")' "$candidate" >/dev/null 2>"$diagnostics"; then
    echo "Skipping non-NDJSON result candidate: $candidate (jq diagnostic: $diagnostics)" >&2
    return 1
  fi

  artifact_validation_report "$candidate" | jq -e '.valid' >/dev/null
}

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
RESULT_RUN_ID="issue92_${STAMP}_$$"
DEVICE_RESULT_PATH="files/real_model_test_results/${RESULT_RUN_ID}.ndjson"
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
result_run_id=$RESULT_RUN_ID
expected_device_result_path=$DEVICE_RESULT_PATH
EOF

echo "Real SLM validation"
echo "Device: $MANUFACTURER $MODEL"
echo "Serial: $SERIAL"
echo "Android: $ANDROID_VERSION (API $API_LEVEL)"
echo "Installed $APP_PACKAGE versionCode: $INSTALLED_VERSION_CODE"
echo "Validation build versionCode: $VALIDATION_VERSION_CODE"
echo "Iterations: $ITERATIONS"
echo "Expected device result: $DEVICE_RESULT_PATH"

# Gradle's connected-test reports do not contain Android Log.i output. Capture
# this run's logcat before testing: app-private NDJSON is preferred, while the
# exact-run structured export survives connected-test package teardown.
adb -s "$SERIAL" logcat -c

set +e
ANDROID_SERIAL="$SERIAL" ./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -PlocalBuild=true \
  -Papp.versionCode="$VALIDATION_VERSION_CODE" \
  -PskipModelDownload=true \
  -Pdebug.embedSecrets=false \
  -Prelease.allowSecrets=false \
  -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_METHOD" \
  -Pandroid.testInstrumentationRunnerArguments.ITERATIONS="$ITERATIONS" \
  -Pandroid.testInstrumentationRunnerArguments.RESULT_RUN_ID="$RESULT_RUN_ID" \
  2>&1 | tee "$RESULT_DIR/gradle.log"
GRADLE_STATUS=${PIPESTATUS[0]}
set -e

adb -s "$SERIAL" logcat -d -v threadtime -s 'RealLiteRtAiFollowup:I' \
  > "$RESULT_DIR/device-logcat.txt" 2>&1 || true

APP_PRIVATE_RESULT="$RESULT_DIR/app-private-extraction.out"
set +e
adb -s "$SERIAL" exec-out run-as "$APP_PACKAGE" cat "$DEVICE_RESULT_PATH" \
  > "$APP_PRIVATE_RESULT" 2> "$RESULT_DIR/result-extraction.stderr"
EXTRACTION_STATUS=$?
set -e

RESULT_SOURCE=""
ARTIFACT_VALID=false
if [[ "$EXTRACTION_STATUS" -eq 0 ]] && is_valid_result "$APP_PRIVATE_RESULT"; then
  cp "$APP_PRIVATE_RESULT" "$RESULT_DIR/results.ndjson"
  ARTIFACT_VALID=true
  RESULT_SOURCE="app-private run-specific NDJSON ($DEVICE_RESULT_PATH)"
else
  LOGCAT_EXPORT="$RESULT_DIR/logcat-export.base64"
  set +e
  awk -F '|' -v run_id="$RESULT_RUN_ID" '
    BEGIN { bad = 0; found = 0 }
    index($1, "REAL_AI_FIXTURE_EXPORT") == 0 { next }
    $2 != run_id { next }
    $3 !~ /^[0-9]+$/ || $4 !~ /^[0-9]+$/ || $5 !~ /^[0-9]+$/ ||
      $6 !~ /^[A-Za-z0-9+\/=]+$/ { bad = 1; next }
    {
      record = $3
      if (!(record in total)) {
        total[record] = $5
        next_chunk[record] = 1
      }
      if ($5 != total[record] || $4 != next_chunk[record]) {
        bad = 1
      } else {
        payload[record] = payload[record] $6
        next_chunk[record]++
        found = 1
      }
    }
    END {
      if (!found) bad = 1
      for (record in total) {
        if (next_chunk[record] - 1 != total[record]) {
          bad = 1
        } else {
          print record "\t" payload[record]
        }
      }
      exit bad
    }
  ' "$RESULT_DIR/device-logcat.txt" | sort -n -k1,1 | cut -f2 > "$LOGCAT_EXPORT"
  LOGCAT_EXPORT_STATUS=${PIPESTATUS[0]}
  set -e

  LOGCAT_RECONSTRUCTED="$RESULT_DIR/logcat-reconstructed.ndjson"
  : > "$LOGCAT_RECONSTRUCTED"
  LOGCAT_DECODE_STATUS=0
  if printf 'TQ==' | base64 -d >/dev/null 2>&1; then
    BASE64_DECODE_FLAG="-d"
  else
    BASE64_DECODE_FLAG="-D"
  fi
  if [[ "$LOGCAT_EXPORT_STATUS" -eq 0 ]]; then
    while IFS= read -r encoded; do
      if ! printf '%s' "$encoded" | base64 "$BASE64_DECODE_FLAG" >> "$LOGCAT_RECONSTRUCTED"; then
        LOGCAT_DECODE_STATUS=1
        break
      fi
      printf '\n' >> "$LOGCAT_RECONSTRUCTED"
    done < "$LOGCAT_EXPORT"
  else
    LOGCAT_DECODE_STATUS=1
  fi

  if [[ "$LOGCAT_DECODE_STATUS" -eq 0 ]] && is_valid_result "$LOGCAT_RECONSTRUCTED"; then
    cp "$LOGCAT_RECONSTRUCTED" "$RESULT_DIR/results.ndjson"
    ARTIFACT_VALID=true
    RESULT_SOURCE="run-specific structured logcat export ($RESULT_RUN_ID)"
  fi
fi

if [[ "$ARTIFACT_VALID" == true ]]; then
  jq -sr '
    ["runId", "fixtureId", "questionId", "category", "iteration", "question", "answer", "rawEval", "rawFollowup", "acceptedFollowup", "productionParseSuccess", "productionPolicyDecision", "expectedDecisionMatch", "canonicalMissingPoints", "deterministicExpectationMatch", "expectedFollowup", "followupBehaviorMatch", "unnecessaryFollowup", "unexpectedNoFollowup", "classification", "expectedLanguage", "languageReviewStatus", "languageReviewNote", "evalTimedOut", "followupTimedOut", "evalError", "followupError", "evalObservedElapsedMs", "followupObservedElapsedMs", "overallElapsedMs"],
    (.[] | [
      .runId, .fixtureId, .nodeId, .category, .iteration, .questionText, .initialAnswer, .rawEval,
      .rawFollowup, .acceptedFollowup, .productionParseSuccess,
      .productionPolicyDecision, .expectedDecisionMatch, (.canonicalMissingPoints | @json),
      .deterministicExpectationMatch, .expectedFollowup, .followupBehaviorMatch,
      .unnecessaryFollowup, .unexpectedNoFollowup, .classification, .expectedLanguage,
      .languageReviewStatus, .languageReviewNote, .evalTimedOut,
      .followupTimedOut, .evalError, .followupError, .evalObservedElapsedMs,
      .followupObservedElapsedMs, .overallElapsedMs
    ]) | @csv
  ' "$RESULT_DIR/results.ndjson" > "$RESULT_DIR/results.csv"
else
  {
    echo "result_extraction_status=$EXTRACTION_STATUS"
    echo "expected_device_result_path=$DEVICE_RESULT_PATH"
    echo "app_private_result=$APP_PRIVATE_RESULT"
    echo "app_private_directory_listing:"
    adb -s "$SERIAL" shell run-as "$APP_PACKAGE" ls -la files/real_model_test_results 2>&1 || true
    echo "logcat_export_status=${LOGCAT_EXPORT_STATUS:-not_attempted}"
    echo "logcat_decode_status=${LOGCAT_DECODE_STATUS:-not_attempted}"
    echo "expected_record_count=$EXPECTED_RECORD_COUNT"
    for candidate in "$APP_PRIVATE_RESULT" "${LOGCAT_RECONSTRUCTED:-}"; do
      [[ -n "$candidate" && -s "$candidate" ]] || continue
      echo "artifact_validation_candidate=$candidate"
      if ! artifact_validation_report "$candidate"; then
        echo "artifact_validation_report=unavailable (candidate is not parseable NDJSON)"
      fi
    done
    echo "device_log_markers:"
    rg 'REAL_AI_FIXTURE_(REPORT_PATH|SUMMARY|RESULT|NDJSON|EXPORT)' "$RESULT_DIR/device-logcat.txt" || true
  } > "$RESULT_DIR/result-extraction-diagnostics.txt"
fi

{
  echo "gradle_status=$GRADLE_STATUS"
  echo "artifact_valid=$ARTIFACT_VALID"
  echo "result_source=${RESULT_SOURCE:-none}"
  echo "result_extraction_status=$EXTRACTION_STATUS"
} >> "$RESULT_DIR/metadata.txt"

if [[ "$GRADLE_STATUS" -ne 0 ]]; then
  echo "Instrumentation test failed; Gradle exit=$GRADLE_STATUS." >&2
  if [[ "$ARTIFACT_VALID" == true ]]; then
    echo "Partial fixture evidence was extracted from: $RESULT_SOURCE" >&2
  else
    echo "No valid result artifact was extracted; see $RESULT_DIR/result-extraction-diagnostics.txt" >&2
  fi
  exit "$GRADLE_STATUS"
fi

if [[ "$ARTIFACT_VALID" != true ]]; then
  echo "Instrumentation passed, but Issue #92 artifact extraction failed." >&2
  echo "Expected: $DEVICE_RESULT_PATH" >&2
  echo "Diagnostics: $RESULT_DIR/result-extraction-diagnostics.txt" >&2
  exit 3
fi

echo
echo "Per-question and category fixture summary"
printf '%-5s %-10s %-5s %-10s %-12s %-10s %-10s %-10s %-10s %-8s %-10s\n' \
  "Question" "Category" "Runs" "ValidEval" "MissingMatch" "Followup" "NoFollowup" "Unnecessary" "DecisionMis" "Failures" "AvgMs"
for NODE in "${QUESTIONS[@]}"; do
  for CATEGORY in "${CATEGORIES[@]}"; do
    RUNS="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category)] | length' "$RESULT_DIR/results.ndjson")"
    VALID_EVAL="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .productionParseSuccess == true and .productionPolicyDecision != "FAILURE")] | length' "$RESULT_DIR/results.ndjson")"
    DETERMINISTIC_COUNT="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .deterministicExpectationMatch != null)] | length' "$RESULT_DIR/results.ndjson")"
    if [[ "$DETERMINISTIC_COUNT" -eq 0 ]]; then MISSING_MATCH="n/a"; else
      MATCHED="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .deterministicExpectationMatch == true)] | length' "$RESULT_DIR/results.ndjson")"
      MISSING_MATCH="$MATCHED/$DETERMINISTIC_COUNT"
    fi
    FOLLOWUP="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .followupAccepted == true)] | length' "$RESULT_DIR/results.ndjson")"
    NO_FOLLOWUP="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .unexpectedNoFollowup == true)] | length' "$RESULT_DIR/results.ndjson")"
    UNNECESSARY="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .unnecessaryFollowup == true)] | length' "$RESULT_DIR/results.ndjson")"
    DECISION_MISMATCH="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and .expectedDecisionMatch == false)] | length' "$RESULT_DIR/results.ndjson")"
    FAILURES="$(jq -s --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category and (.classification == "TIMEOUT" or .classification == "RUNTIME_ERROR"))] | length' "$RESULT_DIR/results.ndjson")"
    AVG_MS="$(jq -sr --arg node "$NODE" --arg category "$CATEGORY" '[.[] | select(.nodeId == $node and .category == $category) | .overallElapsedMs] | if length == 0 then "n/a" else ((add / length) | floor | tostring) end' "$RESULT_DIR/results.ndjson")"
    printf '%-5s %-10s %-5s %-10s %-12s %-10s %-10s %-10s %-10s %-8s %-10s\n' \
      "$NODE" "$CATEGORY" "$RUNS" "$VALID_EVAL/$RUNS" "$MISSING_MATCH" "$FOLLOWUP/$RUNS" "$NO_FOLLOWUP" "$UNNECESSARY" "$DECISION_MISMATCH" "$FAILURES" "$AVG_MS"
  done
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
