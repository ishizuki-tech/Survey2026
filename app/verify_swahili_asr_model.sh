#!/usr/bin/env bash
# Verifies the selected CV14 model both before packaging and after APK extraction.
set -Eeuo pipefail

readonly MODEL_NAME="ggml-small-sw-cv14-q5_0.bin"
readonly MODEL_PATH="models/$MODEL_NAME"
readonly EXPECTED_SIZE=175209680
readonly EXPECTED_SHA256="a8610565fe9ebed140d799b30771c984d85e08eb21f17365845fb4c357a510ce"
readonly SOURCE_URL="https://github.com/ishizuki-tech/Survey2026/releases/download/swahili-whisper-models-v1/$MODEL_NAME"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_file() {
  local file="$1"
  local label="$2"
  [ -f "$file" ] && [ -s "$file" ] || { echo "Swahili ASR $label verification FAIL: missing or empty $file" >&2; return 1; }

  local size magic actual
  size="$(wc -c < "$file" | tr -d '[:space:]')"
  [ "$size" = "$EXPECTED_SIZE" ] || { echo "Swahili ASR $label verification FAIL: size=$size expected=$EXPECTED_SIZE" >&2; return 1; }
  magic="$(dd if="$file" bs=1 count=4 2>/dev/null | LC_ALL=C cat)"
  case "$magic" in lmgg|ggml|GGUF|FUGG) ;; *) echo "Swahili ASR $label verification FAIL: invalid GGML/GGUF header" >&2; return 1 ;; esac
  actual="$(sha256 "$file")"
  [ "$actual" = "$EXPECTED_SHA256" ] || { echo "Swahili ASR $label verification FAIL: sha256=$actual expected=$EXPECTED_SHA256" >&2; return 1; }
  echo "Swahili ASR $label verification PASS: source=$SOURCE_URL path=$MODEL_PATH size=$size sha256=$actual"
}

if [ "${1:-}" = "--apk" ]; then
  [ $# -eq 2 ] || { echo "usage: $0 [--apk APK]" >&2; exit 2; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required" >&2; exit 127; }
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  unzip -p "$2" "assets/$MODEL_PATH" > "$tmp" || { echo "Swahili ASR APK verification FAIL: asset missing from $2" >&2; exit 1; }
  verify_file "$tmp" "APK"
else
  [ $# -eq 0 ] || { echo "usage: $0 [--apk APK]" >&2; exit 2; }
  verify_file "${MODEL_FILE:-src/main/assets/$MODEL_PATH}" "model"
fi
