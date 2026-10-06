#!/usr/bin/env bash
# ============================================================
# ✅ Whisper models downloader — macOS Bash 3.2 compatible
# ------------------------------------------------------------
# • No associative arrays (works on /bin/bash 3.2)
# • Atomic writes via *.part → mv
# • Resume (-C -), retries, timeouts, backoff
# • Header verification supports:
#     - ggml magic written little-endian ("lmgg")
#     - ggml string ("ggml") (just in case)
#     - GGUF ("GGUF") / ("FUGG") (defensive)
# • Env overrides:
#     MODEL_DIR / MODEL_URL / MODEL_NAMES
#     SWAHILI_CV14_URL / SWAHILI_CV14_SHA256
#     NDIZI_GGML_Q4_URL / NDIZI_GGML_Q5_URL
#     DEBUG=1 for verbose logs
# ============================================================

set -Eeuo pipefail

# --- Config (env-overridable) --------------------------------
MODEL_DIR="${MODEL_DIR:-src/main/assets/models}"
MODEL_URL="${MODEL_URL:-https://huggingface.co/ggerganov/whisper.cpp/resolve/main}"

# space-separated list
MODEL_NAMES="${MODEL_NAMES:-ggml-small-q5_1.bin ggml-small-sw-cv14-q5_0.bin}"

# model-specific URL overrides
NDIZI_GGML_Q4_URL="${NDIZI_GGML_Q4_URL:-https://huggingface.co/smutuvi/ndizi-whisper-large-turbo-v3-GGUF/resolve/main/ggml-model-q4_0.bin}"
NDIZI_GGML_Q5_URL="${NDIZI_GGML_Q5_URL:-https://huggingface.co/smutuvi/ndizi-whisper-large-turbo-v3-GGUF/resolve/main/ggml-model-q5_0.bin}"
SWAHILI_CV14_NAME="ggml-small-sw-cv14-q5_0.bin"
SWAHILI_CV14_URL="${SWAHILI_CV14_URL:-https://github.com/ishizuki-tech/Survey2026/releases/download/swahili-whisper-models-v1/ggml-small-sw-cv14-q5_0.bin}"
SWAHILI_CV14_SHA256="${SWAHILI_CV14_SHA256:-a8610565fe9ebed140d799b30771c984d85e08eb21f17365845fb4c357a510ce}"
SWAHILI_CV14_SIZE=175209680

DEBUG="${DEBUG:-0}"

# --- Preconditions -------------------------------------------
need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "❌ Required command '$1' not found"; exit 127; }; }
need_cmd curl
need_cmd shasum
mkdir -p "$MODEL_DIR"

dbg() { if [ "$DEBUG" = "1" ]; then echo "[DEBUG] $*"; fi; }

# Return final URL for a given model name (no associative arrays)
url_for_model() {
  local name="$1"
  if [ "$name" = "ggml-model-q4_0.bin" ]; then
    echo "$NDIZI_GGML_Q4_URL"
  elif [ "$name" = "ggml-model-q5_0.bin" ]; then
    echo "$NDIZI_GGML_Q5_URL"
  elif [ "$name" = "$SWAHILI_CV14_NAME" ]; then
    echo "$SWAHILI_CV14_URL"
  else
    echo "$MODEL_URL/$name"
  fi
}

is_swahili_cv14() {
  [ "$1" = "$SWAHILI_CV14_NAME" ]
}

verify_swahili_cv14_identity() {
  local file="$1"
  local name="$2"

  if ! is_swahili_cv14 "$name"; then
    return 0
  fi

  local size
  size="$(wc -c < "$file" | tr -d '[:space:]')"
  if [ "$size" != "$SWAHILI_CV14_SIZE" ]; then
    echo "❌ Swahili ASR model verification FAIL: $name has $size bytes; expected $SWAHILI_CV14_SIZE"
    return 1
  fi

  local actual_sha
  actual_sha="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [ "$actual_sha" != "$SWAHILI_CV14_SHA256" ]; then
    echo "❌ Swahili ASR model verification FAIL: $name SHA-256 mismatch; expected $SWAHILI_CV14_SHA256 got $actual_sha"
    return 1
  fi

  echo "✅ Swahili ASR model verification PASS: source=$SWAHILI_CV14_URL file=$name size=$size sha256=$actual_sha"
}

# Read first 4 bytes as ASCII safely (works on macOS)
read_magic4() {
  local file="$1"
  dd if="$file" bs=1 count=4 2>/dev/null | LC_ALL=C cat
}

# Verify model header:
# - ggml models often appear as "lmgg" because magic 0x67676d6c is written little-endian
# - GGUF models begin with "GGUF"
verify_header() {
  local file="$1"
  local name="$2"

  if [ ! -s "$file" ]; then
    echo "⚠️  File is empty: $name"
    return 1
  fi

  local magic
  magic="$(read_magic4 "$file")"

  # Accepted magics:
  #   "lmgg"  -> ggml magic written as little-endian uint32 (common in ggml-derived formats)
  #   "ggml"  -> plain ASCII (rare, but harmless to accept)
  #   "GGUF"  -> GGUF format
  #   "FUGG"  -> defensive (endian/confused writer)
  case "$magic" in
    lmgg|ggml|GGUF|FUGG)
      dbg "Header OK for $name: '$magic'"
      return 0
      ;;
    *)
      echo "⚠️  Unexpected header (first 4 bytes) for $name: '$(printf "%s" "$magic")' (expected ggml/GGUF)"
      return 1
      ;;
  esac
}

verify_model() {
  local file="$1"
  local name="$2"
  verify_header "$file" "$name" && verify_swahili_cv14_identity "$file" "$name"
}

download() {
  local name="$1"
  local url; url="$(url_for_model "$name")"
  local tmp="$MODEL_DIR/${name}.part"
  local out="$MODEL_DIR/${name}"

  if [ -f "$out" ] && [ -s "$out" ]; then
    if verify_model "$out" "$name"; then
      echo "✅ $name already exists. Skipping."
      return 0
    fi
    echo "⚠️  Existing file looks wrong; re-downloading: $name"
    rm -f "$out" || true
  fi

  echo "⬇️  Downloading $name"
  echo "    → $url"

  # 5 attempts with simple backoff
  for attempt in 1 2 3 4 5; do
    if curl -fL \
        --retry 5 --retry-delay 2 --retry-max-time 180 \
        --connect-timeout 20 --max-time 0 \
        --speed-time 30 --speed-limit 1024 \
        -H "User-Agent: curl/8.x (WhispersCpp-Android)" \
        -C - -o "$tmp" "$url"; then

      mv -f "$tmp" "$out"

      if ! verify_model "$out" "$name"; then
        echo "⚠️  Downloaded file failed verification; retrying: $name"
        rm -f "$out" || true
      else
        echo "✅ Download complete: $name"
        return 0
      fi
    else
      echo "⚠️  Attempt $attempt failed for $name"
      sleep $(( attempt * 2 ))
    fi
  done

  echo "❌ Failed to download: $name"
  return 1
}

# --- Main -----------------------------------------------------
fail=0
for model in $MODEL_NAMES; do
  if ! download "$model"; then
    fail=1
  fi
done

if [ "$fail" = "1" ]; then
  echo "❌ One or more downloads failed."
  exit 1
fi

echo "🎉 All models present in: $MODEL_DIR"
