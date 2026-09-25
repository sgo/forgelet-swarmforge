#!/usr/bin/env zsh
# Prune the throwaway output a project's acceptance runs leave behind.
#
# Throwaway output accumulates in two places, both under a worktree's build/:
#   build/acceptance/run/<scenario>/          one Synapse and clients per scenario
#   build/acceptance-mutation/<feature>/mutations/mN/   one run per mutant
# Neither is ever removed, so they grow without limit — 27 GB of scenarios and
# another 5 GB of mutants across one project's worktrees, observed 2026-09-23. A
# build tool would put this under target/ and erase it on clean; this is the
# equivalent, with the newest kept per directory so a failure can still be looked at.
#
# Usage:
#   prune_build_debris.sh <forge-root> [--keep <n>] [--dry-run]
#
# It touches only directories ending in build/acceptance/run or
# build/acceptance-mutation/<feature>/mutations, and never the binaries beside them
# in build/acceptance/bin — the bridge runs from there.
set -euo pipefail
setopt null_glob          # an unmatched debris glob is not an error, just nothing

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

root="${1:-}"; shift || true
keep=20          # scenario runs are ~3 MB each, so a handful of them is cheap to keep
keep_mutants=1   # a mutant run is ~850 MB, and its result is recorded in the manifest
dry=0
while (( $# )); do
  case "$1" in
    --keep) keep="$2"; shift 2 ;;
    --keep-mutants) keep_mutants="$2"; shift 2 ;;
    --dry-run) dry=1; shift ;;
    *) echo "prune_build_debris: unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -d "$root/projects" ]] || { echo "Not a forge root (no projects/): $root" >&2; exit 2; }
root="$(cd "$root" && pwd)"

debris_dirs() {
  for project in "$root"/projects/*; do
    [[ -d "$project" ]] || continue
    for base in "$project" "$project"/.worktrees/*; do
      [[ -d "$base/build/acceptance/run" ]] && print -- "$base/build/acceptance/run"
      for feature in "$base"/build/acceptance-mutation/*/mutations; do
        [[ -d "$feature" ]] && print -- "$feature"
      done
    done
  done
}

prune_dir() {
  local dir="$1" limit="$2"
  # Newest first, by modification time, so the newest `keep` survive and the rest go.
  local -a entries=()
  while IFS= read -r s; do entries+=("$s"); done < <(ls -dt "$dir"/* 2>/dev/null || true)
  (( ${#entries[@]} > limit )) || return 0
  local s kb
  for s in "${entries[@]:$limit}"; do
    kb=$(du -sk "$s" 2>/dev/null | awk '{print $1}')
    if (( dry )); then
      print -- "would remove $s (${kb:-0} KB)"
    else
      rm -rf -- "$s"
    fi
    total=$(( total + 1 ))
    freed_kb=$(( freed_kb + ${kb:-0} ))
  done
}

total=0
freed_kb=0
while read -r dir; do
  [[ -n "$dir" ]] || continue
  # Only ever a debris directory: refuse anything else, however it got here.
  [[ "$dir" == */build/acceptance/run || "$dir" == */build/acceptance-mutation/*/mutations ]] \
    || { echo "refusing to touch $dir" >&2; continue; }
  if [[ "$dir" == */build/acceptance/run ]]; then
    prune_dir "$dir" "$keep"
  else
    prune_dir "$dir" "$keep_mutants"
  fi
done < <(debris_dirs)

if (( dry )); then
  print -- "dry run: $total directories, $(( freed_kb / 1024 )) MB would be freed"
else
  print -- "pruned $total directories, freed $(( freed_kb / 1024 )) MB (kept the newest $keep scenario runs and $keep_mutants mutant runs per directory)"
fi
