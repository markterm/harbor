#!/usr/bin/env bash
# Apply patches/*.patch to the fm1-emu submodule (idempotent: skips what is already applied).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/third_party/fm1-emu"
for p in "$ROOT"/patches/*.patch; do
  if git apply --reverse --check "$p" 2>/dev/null; then
    echo "already applied: $(basename "$p")"
  else
    git apply "$p"
    echo "applied: $(basename "$p")"
  fi
done
