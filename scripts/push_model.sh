#!/usr/bin/env bash
set -euo pipefail

# Usage: push_model.sh /path/to/model.litertlm [PACKAGE]
MODEL_IN="${1:-}"
PKG="${2:-com.negi.survey}"

if [[ -z "${MODEL_IN}" || ! -f "${MODEL_IN}" ]]; then
  echo "❌ Model file not found: ${MODEL_IN:-<empty>}" >&2
  exit 1
fi

NAME="$(basename "$MODEL_IN")"

echo "📦 MODEL: $MODEL_IN"
echo "🎯 PKG  : $PKG"
echo "➡️  temp : /data/local/tmp/$NAME"

# 1) まず /data/local/tmp に push
adb push "$MODEL_IN" "/data/local/tmp/$NAME"

# 2) run-as で内部 files/ へコピー（最優先）
echo "🔐 try run-as (internal files/)"
if adb shell run-as "$PKG" sh -c 'mkdir -p files/models && cp "/data/local/tmp/'"$NAME"'" "files/models/'"$NAME"'" && ls -lh "files/models/'"$NAME"'"' ; then
  echo "✅ placed at: /data/user/0/$PKG/files/models/$NAME"
  exit 0
fi

# 3) run-as が使えない場合は外部 app dir（ユーザー0 & 現在ユーザー）へ
echo "⚠️ run-as failed. Fallback to external app dir."
CUR="$(adb shell am get-current-user | tr -d $'\r')"
for U in 0 "$CUR"; do
  EXTDIR="/storage/emulated/$U/Android/data/$PKG/files/models"
  echo "📁 mkdir -p $EXTDIR"
  adb shell "mkdir -p '$EXTDIR'"
  echo "⬆️  adb push -> $EXTDIR/$NAME"
  adb push "/data/local/tmp/$NAME" "$EXTDIR/$NAME"
  adb shell "ls -lh '$EXTDIR/$NAME'" || true
done

echo "✅ done."
