#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
[[ -f "$root/.swarmforge/single-agent" ]] || exit 0

# The host records the operator-supplied mission before any agent card exists.
# That one operator-owned file is not agent work and must not wait for a spec.
staged="$(git -C "$root" diff --cached --name-only --diff-filter=ACMRTUXB)"
[[ "$staged" == "mission.md" ]] && exit 0

# Opening a project records refreshed runtime files as one managed checkpoint.
# This exception is deliberately narrow: the invoking process must identify the
# managed sync, and every staged path must be one forge.bb owns.
if [[ "${SWARMFORGE_MANAGED_RUNTIME_SYNC:-}" == "1" && -n "$staged" ]]; then
  managed_runtime=true
  while IFS= read -r path; do
    case "$path" in
      swarmforge/scripts/*|\
      swarmforge/roles/*|\
      swarmforge/constitution/articles/*|\
      swarmforge/swarmforge.conf|\
      swarmforge/constitution.prompt)
        ;;
      *)
        managed_runtime=false
        ;;
    esac
  done <<< "$staged"
  [[ "$managed_runtime" == "true" ]] && exit 0
fi

export PATH="/opt/homebrew/bin:${HOME:-}/.local/bin:$PATH"

helper="${SWARMFORGE_STAGE_HELPER:-}"
if [[ -z "$helper" && -f "$root/swarmforge/scripts/stage.bb" ]]; then
  helper="$root/swarmforge/scripts/stage.bb"
fi
if [[ -z "$helper" && -f "$root/swarmforge/scripts/stage.sh" ]]; then
  helper="$root/swarmforge/scripts/stage.sh"
fi
if [[ -z "$helper" ]]; then
  runtime_config="${FORGELET_STAGE_RUNTIME_CONFIG:-${XDG_CONFIG_HOME:-${HOME:-}/.config}/forgelet/stage-runtime.json}"
  if [[ -f "$runtime_config" && ! -L "$runtime_config" ]]; then
    runtime_mode="$(stat -f '%Lp' "$runtime_config" 2>/dev/null || stat -c '%a' "$runtime_config" 2>/dev/null || true)"
    if [[ "$runtime_mode" =~ ^[0-7]+$ ]] && (( (8#$runtime_mode & 077) == 0 )); then
      stage_home="$(
        python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); value=data.get("stageHome"); print(value if isinstance(value,str) else "")' \
          "$runtime_config" 2>/dev/null || true
      )"
    fi
    if [[ -n "${stage_home:-}" && -f "$stage_home/stage.sh" && ! -L "$stage_home/stage.sh" ]]; then
      helper="$stage_home/stage.sh"
    elif [[ -n "${stage_home:-}" && -f "$stage_home/stage.bb" && ! -L "$stage_home/stage.bb" ]]; then
      helper="$stage_home/stage.bb"
    fi
  fi
fi

if [[ -z "$helper" || ( ! -f "$helper" && ! -L "$helper" ) ]]; then
  cat >&2 <<'MSG'
Commit refused: no stage approval interface is available.
Set SWARMFORGE_STAGE_HELPER or configure the MCP stage runtime's stageHome.
The approval check fails closed; do not use --no-verify.
MSG
  exit 1
fi

if [[ "$helper" == *.bb ]]; then
  approval_output="$(
    bb "$helper" approval-status --root "$root" 2>/dev/null || true
  )"
else
  approval_output="$(
    "$helper" approval-status --root "$root" 2>/dev/null || true
  )"
fi

if [[ "$approval_output" != "approved" ]]; then
  cat >&2 <<'MSG'
Commit refused: the current spec bundle is not approved.
If approval was expected, the stage approval interface was unavailable or
returned invalid state.
Run:
  bb swarmforge/scripts/stage.bb propose spec <files...>
  bb swarmforge/scripts/stage.bb approve <proposal> --words "<operator words>"
Do not use --no-verify.
MSG
  exit 1
fi
