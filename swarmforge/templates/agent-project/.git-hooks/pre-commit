#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
[[ -f "$root/.swarmforge/single-agent" ]] || exit 0

stage="$root/swarmforge/scripts/stage.bb"
[[ -f "$stage" ]] || exit 0

export PATH="/opt/homebrew/bin:$HOME/.local/bin:$PATH"
approved="$(
  bb "$stage" status --json --root "$root" 2>/dev/null \
    | python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("spec_approved", False)).lower())' \
      2>/dev/null || true
)"

if [[ "$approved" != "true" ]]; then
  cat >&2 <<'MSG'
Commit refused: the current spec bundle is not approved.
Run:
  bb swarmforge/scripts/stage.bb propose spec <files...>
  bb swarmforge/scripts/stage.bb approve <proposal> --words "<operator words>"
Do not use --no-verify.
MSG
  exit 1
fi
