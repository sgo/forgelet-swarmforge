#!/usr/bin/env zsh
#
# Does an upgrade leave this forge alone?
#
# The composition is a one-way street: get-swarm-forge replaces a forge's shared
# scripts, patches the kit's tools over them, and rewrites the packs. What this
# exercise checks is whether running it again on an already-composed forge changes
# anything - and whether the changes it does make are the ones somebody has
# accepted and written down.
#
# It works on a clone, never on the forge it is pointed at: it clones the forge's
# own origin into a scratch directory under the forge's tmp/, composes that clone,
# commits the result as an upgrade would, composes again, and compares.
#
# The baseline - <forge>/swarmforge/update-exercise.baseline - is the forge's own,
# one line per file an upgrade is allowed to change, with the reason. A change that
# is not listed fails, and so does a listed file that no longer changes, because a
# fixed loss and a stale excuse should both be visible rather than remembered.
#
# Usage:
#   update-exercise.sh [<forge-root>]        (default: the current directory)
#
# Exits 0 when an upgrade is a no-op except for what the baseline allows, 1 when it
# is not, and 2 when the exercise cannot be run (no origin, no helper) - which is a
# fact about the forge, not a fault in it.
set -euo pipefail

# zsh sets $0 to the function's name inside a function, so the script's own path is
# taken once, here, where $0 is still the script.
script_path="${0:A}"

usage() {
  sed -n '3,25p' "$script_path" | sed 's/^# \{0,1\}//'
}

forge="${1:-$PWD}"
if [[ ! -d "$forge" || ! -d "$forge/projects" ]]; then
  echo "update-exercise: not a forge root (no projects/): $forge" >&2
  usage >&2
  exit 2
fi
forge="$(cd "$forge" && pwd)"
product="${SWARMFORGE_UPDATE_PRODUCT:-project-manager}"
baseline="$forge/swarmforge/update-exercise.baseline"

origin="$(git -C "$forge" remote get-url origin 2>/dev/null || true)"
if [[ -z "$origin" ]]; then
  echo "update-exercise: this forge has no origin, so there is nothing to clone; the exercise compares an upgrade against what the remote holds" >&2
  exit 2
fi
helper="$(command -v get-swarm-forge || true)"
if [[ -z "$helper" ]]; then
  echo "update-exercise: no get-swarm-forge on PATH; install the helper from the layer's README, since the exercise runs the composition" >&2
  exit 2
fi

scratch="$forge/tmp/update-exercise-$(date +%Y%m%dT%H%M%S)-$$"
mkdir -p "$scratch"
cleanup() {
  local code=$?
  if [[ -d "$scratch" ]]; then
    if [[ $code -eq 0 ]]; then
      rm -rf "$scratch"
    else
      echo "update-exercise: failed, and left its work at $scratch" >&2
    fi
  fi
}
trap cleanup EXIT

clone="$scratch/forge"
git clone -q -- "$origin" "$clone"

compose() {
  local log="$1"
  if ! ( cd "$clone" && "$helper" "$product" ) >"$log" 2>&1; then
    echo "update-exercise: the composition failed; its own words:" >&2
    tail -20 "$log" >&2
    exit 1
  fi
}

# What an upgrade would change here.
compose "$scratch/compose-first.log"
changed=( ${(f)"$(git -C "$clone" diff --name-only; git -C "$clone" ls-files --others --exclude-standard)"} )
changed=( ${(u)changed:#} )

echo "=== what an upgrade changes"
if (( ${#changed} == 0 )); then
  echo "nothing: an upgrade is a no-op on this forge"
else
  git -C "$clone" diff --numstat | awk '{printf "%4s+ %4s-  %s\n", $1, $2, $3}'
  # `path` is tied to PATH in zsh, so a loop variable by that name rewrites the
  # environment and the next command is not found. It is `file` here.
  for file in "${changed[@]}"; do
    git -C "$clone" ls-files --error-unmatch -- "$file" >/dev/null 2>&1 || echo "  new file  $file"
  done
fi

# The baseline's own words: <path><TAB>why, comments and blanks ignored.
allowed=()
if [[ -f "$baseline" ]]; then
  while IFS=$'\t' read -r file why; do
    [[ -z "${file// }" || "$file" == \#* ]] && continue
    allowed+=("$file")
  done < "$baseline"
fi

failures=0
for file in "${changed[@]}"; do
  if (( ${allowed[(Ie)$file]} == 0 )); then
    echo "FAIL  $file changed and is not in the baseline"
    failures=$((failures + 1))
  fi
done
for file in "${allowed[@]}"; do
  if (( ${changed[(Ie)$file]} == 0 )); then
    echo "FAIL  the baseline still excuses $file, which no longer changes; remove the line"
    failures=$((failures + 1))
  fi
done

# The second compose: an upgrade of an upgraded forge should change nothing.
git -C "$clone" -c user.name="Update exercise" -c user.email="update-exercise@localhost" \
    add -A >/dev/null
git -C "$clone" -c user.name="Update exercise" -c user.email="update-exercise@localhost" \
    commit -q -m "the upgrade, as it would be committed" >/dev/null
compose "$scratch/compose-second.log"
second="$(git -C "$clone" status --porcelain)"
if [[ -n "$second" ]]; then
  echo "FAIL  an upgrade of an upgraded forge is not a no-op:"
  echo "$second"
  failures=$((failures + 1))
fi

if (( failures > 0 )); then
  echo "update-exercise: $failures thing(s) an upgrade would do that nobody has accepted"
  echo "the baseline is $baseline - one line per accepted file, tab, then why"
  exit 1
fi

echo "update-exercise: an upgrade is a no-op here, except for the ${#allowed} file(s) the baseline accepts"
