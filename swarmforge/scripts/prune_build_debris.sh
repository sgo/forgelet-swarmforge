#!/usr/bin/env zsh
# Prune the throwaway output a project's acceptance runs leave behind.
#
# Throwaway output accumulates in two places, both under a worktree's build/:
#   build/acceptance/run/<scenario>/              one Synapse and clients per scenario
#   build/acceptance-mutation/**/mutations/mN/    one run per mutant
# Neither is ever removed, so they grow without limit — 27 GB of scenarios and
# another 5 GB of mutants across one project's worktrees, observed 2026-09-23. A
# build tool would put this under target/ and erase it on clean; this is the
# equivalent, with the newest kept per directory so a failure can still be looked at.
#
# Two things the count alone does not see. The mutation pass roots its work
# wherever it is run from, so the directory of mutants is the one named mutations
# anywhere below the mutation tree, the way the project's own cleaner reads it.
# And a once-a-day prune lands whenever it lands: a mutation pass puts a burst of
# runs under one directory at once, so the count alone would empty the run
# happening around this prune. A directory of runs something wrote in this
# recently holds a run in flight, and is left alone for this pass rather than
# taken apart under it.
#
# Usage:
#   prune_build_debris.sh <forge-root> [--keep <n>] [--keep-mutants <n>]
#                         [--in-flight <minutes>] [--dry-run]
#
# It touches only directories ending in build/acceptance/run or
# build/acceptance-mutation/**/mutations, and never the binaries beside them in
# build/acceptance/bin — the bridge runs from there.
set -euo pipefail
setopt null_glob          # an unmatched debris glob is not an error, just nothing

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

root="${1:-}"; shift || true
keep=20          # scenario runs are ~3 MB each, so a handful of them is cheap to keep
keep_mutants=1   # a mutant run is ~850 MB, and its result is recorded in the manifest
# How recently a run must have written in a directory of runs for that directory
# to be one a run is still using: longer than any single scenario, and longer
# than a mutant the runner gives its own minutes to finish in.
in_flight_minutes=15
dry=0
while (( $# )); do
  case "$1" in
    --keep) keep="$2"; shift 2 ;;
    --keep-mutants) keep_mutants="$2"; shift 2 ;;
    --in-flight) in_flight_minutes="$2"; shift 2 ;;
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
      # Wherever the mutation pass rooted its work: one directory of mutants per
      # feature, at whatever depth the pass put it.
      for mutations in "$base"/build/acceptance-mutation/**/mutations(N/); do
        print -- "$mutations"
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
  # What says whether a run is still in here: something written within the
  # window. The reading is over the whole directory rather than its newest run,
  # because a run started earlier than a burst of short ones beside it is the
  # run this prune would otherwise take out from under.
  local recent; recent="$(find "$dir" -mmin "-$in_flight_minutes" -print -quit 2>/dev/null || true)"
  if [[ -n "$recent" ]]; then
    print -- "left the runs under $dir alone: $recent was written within the last $in_flight_minutes minutes"
    deferred=$(( deferred + 1 ))
    return 0
  fi
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
deferred=0
while read -r dir; do
  [[ -n "$dir" ]] || continue
  # Only ever a debris directory: refuse anything else, however it got here. The
  # mutation tree holds one directory of mutants per pass, so it is a directory
  # named mutations below it, at any depth.
  if [[ "$dir" == */build/acceptance/run ]]; then
    prune_dir "$dir" "$keep"
  elif [[ "$dir" == */build/acceptance-mutation/* && "${dir:t}" == mutations ]]; then
    prune_dir "$dir" "$keep_mutants"
  else
    echo "refusing to touch $dir" >&2
    continue
  fi
done < <(debris_dirs)

# The summary is the whole of what a caller that keeps one line hears, so a
# directory left alone because a run is still using it is named here too.
if (( dry )); then
  summary="dry run: $total directories, $(( freed_kb / 1024 )) MB would be freed"
else
  summary="pruned $total directories, freed $(( freed_kb / 1024 )) MB (kept the newest $keep scenario runs and $keep_mutants mutant runs per directory)"
fi
if (( deferred > 0 )); then
  what=directories; using=them
  if (( deferred == 1 )); then
    what=directory; using=it
  fi
  summary="$summary; left the runs under $deferred $what alone because a run is still using $using"
fi
print -- "$summary"
