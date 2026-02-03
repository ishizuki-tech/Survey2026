#!/usr/bin/env bash
set -Eeuo pipefail

# -------------------------------------------------------
# Gemma 3n E4B IT (LiteRT LM) downloader (single file)
# -------------------------------------------------------
# Target:
#   https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm
#
# Usage examples:
#   ./download_gemma3n.sh
#   ./download_gemma3n.sh --dir ./models
#   HF_TOKEN=hf_xxx ./download_gemma3n.sh
#   ./download_gemma3n.sh --sha256 <expected_sha256_hex>
#   ./download_gemma3n.sh --no-checksum
#   ./download_gemma3n.sh --quiet
#
# Options:
#   --dir <path>        : download directory (default: .)
#   --token <token>     : HF token (or set HF_TOKEN env)
#   --sha256 <hex>      : expected sha256 of the file (optional)
#   --no-checksum       : skip checksum verification
#   --quiet             : less verbose output
#   --help|-h           : show this help
#
# Exit codes:
#   1 generic error | 2 missing tools | 3 checksum mismatch

# ---------- constants ----------
FILE_NAME="gemma-3n-E4B-it-int4.litertlm"
BASE_URL="https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main"
FILE_URL="${BASE_URL}/${FILE_NAME}"

# ---------- defaults ----------
MODEL_DIR="."
HF_TOKEN="${HF_TOKEN:-}"
EXPECTED_SHA=""
USE_CHECKSUM=true
QUIET=false

# ---------- helpers ----------
trap 'echo "❌ Error on line $LINENO: $BASH_COMMAND" >&2' ERR

has_cmd() { command -v "$1" >/dev/null 2>&1; }

log() { [[ "$QUIET" == false ]] && echo -e "$*"; }

usage() {
  grep -E '^#( |$)' "$0" | sed -E 's/^# ?//'
}

sha256_file() {
  if has_cmd shasum; then shasum -a 256 "$1" | awk '{print $1}'
  elif has_cmd sha256sum; then sha256sum "$1" | awk '{print $1}'
  else
    log "⚠️  No sha256 tool (shasum/sha256sum). Skipping checksum."
    return 2
  fi
}

# Return 0 if curl supports --fail-with-body (>= 7.76.0)
curl_supports_fail_with_body() {
  local v a b c x y z
  v="$(curl -V 2>/dev/null | head -n1 | awk '{print $2}')" || return 1
  IFS=. read -r a b c <<<"${v}.0.0"
  IFS=. read -r x y z <<<"7.76.0"
  (( a > x )) && return 0
  (( a < x )) && return 1
  (( b > y )) && return 0
  (( b < y )) && return 1
  (( c >= z )) && return 0 || return 1
}

fetch_atomic() {
  local url="$1"
  local dest="$2"
  local tmp="${dest}.part"
  local hdr_auth=()
  local hdr_accept=( -H "Accept: application/octet-stream" )

  [[ -n "${HF_TOKEN:-}" ]] && hdr_auth=( -H "Authorization: Bearer $HF_TOKEN" )

  if has_cmd curl; then
    # どちらか一方のみ採用（同時指定は不可）
    local args=(-L --retry 5 --retry-delay 2 --continue-at -)
    if curl_supports_fail_with_body; then
      args+=(--fail-with-body)
    else
      args+=(--fail)
    fi

    curl "${args[@]}" \
         "${hdr_auth[@]}" "${hdr_accept[@]}" \
         "${url}?download=1" -o "$tmp"
  elif has_cmd wget; then
    local hdrs=()
    [[ -n "${HF_TOKEN:-}" ]] && hdrs+=( --header="Authorization: Bearer $HF_TOKEN" )
    hdrs+=( --header="Accept: application/octet-stream" )
    wget -O "$tmp" -c --tries=5 --timeout=30 "${hdrs[@]}" "${url}?download=1"
  else
    echo "❌ Neither curl nor wget is installed." >&2
    exit 2
  fi

  mv -f "$tmp" "$dest"
}

verify_checksum_if_requested() {
  local file="$1"
  [[ "$USE_CHECKSUM/$(printf '%s' "$EXPECTED_SHA")" == "true/" ]] && return 0
  [[ "$USE_CHECKSUM" == false ]] && return 0

  local got
  if ! got="$(sha256_file "$file")"; then
    # sha256ツールが無い環境 -> 検証スキップ
    return 0
  fi

  if [[ -n "$EXPECTED_SHA" ]]; then
    if [[ "$got" != "$EXPECTED_SHA" ]]; then
      echo "❌ Checksum mismatch for $file" >&2
      echo "   expected: $EXPECTED_SHA" >&2
      echo "   got     : $got" >&2
      exit 3
    fi
    log "🔒 Checksum OK: $file"
  else
    log "ℹ️  No expected sha256 provided; skipping strict verification."
  fi
}

# ---------- parse args ----------
while (( "$#" )); do
  case "${1:-}" in
    --dir)          MODEL_DIR="${2:?}"; shift 2 ;;
    --token)        HF_TOKEN="${2:?}"; shift 2 ;;
    --sha256)       EXPECTED_SHA="${2:?}"; shift 2 ;;
    --no-checksum)  USE_CHECKSUM=false; shift ;;
    --quiet)        QUIET=true; shift ;;
    --help|-h)      usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

# ---------- prepare ----------
mkdir -p "$MODEL_DIR"
cd "$MODEL_DIR"

if [[ "$FILE_URL" =~ ^https?://huggingface.co/ ]] && [[ -z "${HF_TOKEN:-}" ]]; then
  log "ℹ️  No HF token provided. If the repo is private or rate-limited, set HF_TOKEN or use --token."
fi

# ---------- download ----------
if [[ -f "$FILE_NAME" && -s "$FILE_NAME" ]]; then
  log "✅ $FILE_NAME already exists."
else
  log "⬇️  Downloading $FILE_NAME ..."
  fetch_atomic "$FILE_URL" "$FILE_NAME"
  if [[ ! -s "$FILE_NAME" ]]; then
    echo "❌ Download failed or empty file: $FILE_NAME" >&2
    exit 1
  fi
  log "✅ Download complete: $(pwd)/$FILE_NAME"
fi

# ---------- verify ----------
verify_checksum_if_requested "$FILE_NAME"

log "🎉 Done."
