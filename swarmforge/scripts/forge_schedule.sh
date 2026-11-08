#!/usr/bin/env zsh
# Forge schedule: the machine's cadence for a forge. One pass runs the stall
# watch over every forge root it serves, and then a doorbell pass for each root,
# so a request that never reached its role is rung with nobody asking.
#
# The tools stay separate and so do their rules: the watch notices a role that
# holds work and is not moving, and the doorbell notices a message the operator
# sent that never arrived. This script is only the cadence - what the machine
# runs on a timer, and the evidence that it ran.
#
# Usage:
#   forge_schedule.sh run <forge-root>...
#
# Every pass runs even when another fails, so a broken one cannot take the rest
# down; a root that cannot be served is named rather than skipped in silence;
# and the heartbeat and the log live in the first root, which is where the
# agent that runs this was installed from.
#
# The cadence is also the only thing that runs whether or not a session is up, so
# it is what cleans up after the forge: the forge's own scratch is swept by age -
# untouched since, not a name pattern, because the honest question about what a
# failed run left is whether anyone is still looking at it - and its own log is
# kept a day to a file, because age cannot judge a file this writes every minute.
# The projects take the second half through the layer's own pruner, once a day
# and only when the forge carries it: the pass brings the cadence, not the policy.
set -euo pipefail

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# How long the pass leaves the forge's own scratch and its own log alone, and
# how often it hands the projects to the layer's pruner: a week is what a week of
# either costs, and a walk over every worktree is a thing to do once a day.
KEEP_DAYS=7

# The layer's own pruner, which the pass hands the projects to: the kit ships the
# cadence and the layer ships the rule, so the pass looks for it in the forge's
# own scripts and names the forge it could not prune when it is not there.
PRUNER=prune_build_debris.sh

# The schedule's own log for today, and the file every word the pass says is
# appended to. The day is in the name because the file is written every minute:
# its mtime is always now, so age would never touch one unbounded file.
schedule_log() { echo "$1/.swarmforge/stall-watch-$(date -u '+%Y-%m-%d').log"; }
schedule_log_file=""

# say writes what the pass says to whoever ran it and to its own log, so the log
# is the pass's own record rather than whatever the machine's redirect caught.
say() {
  print -r -- "$1"
  [[ -n "$schedule_log_file" ]] && print -r -- "$1" >> "$schedule_log_file"
  return 0
}

# newest_mtime is the last time anything in a tree was touched: a scratch
# directory someone is still writing in is not one nobody has looked at.
newest_mtime() {
  find "$1" -exec stat -f %m {} + 2>/dev/null | sort -rn | head -1
}

# trim_scratch sweeps the forge's own tmp: whatever nobody has touched for longer
# than the bound goes, whatever its name, and the pass says what it removed. Only
# a directory goes: what a failed run leaves is a directory, and a loose file in
# the forge's tmp is somebody's own - the answers a person writes for the
# dashboard live there. A tree the pass just watched somebody write in is left
# without walking it, which keeps a minute's pass off a scratch directory of any
# size.
trim_scratch() {
  local root="$1" entry own newest cutoff days
  [[ -d "$root/tmp" ]] || return 0
  cutoff=$(( $(date +%s) - KEEP_DAYS * 86400 ))
  for entry in "$root"/tmp/*(N); do
    [[ -d "$entry" ]] || continue
    own="$(stat -f %m "$entry" 2>/dev/null)"
    [[ -n "$own" ]] || continue
    (( own < cutoff )) || continue
    newest="$(newest_mtime "$entry")"
    [[ -n "$newest" ]] || continue
    if (( newest < cutoff )); then
      days=$(( ( $(date +%s) - newest ) / 86400 ))
      rm -rf -- "$entry"
      say "removed the scratch $entry, untouched for $days days"
    fi
  done
}

# rotate_log keeps the schedule's own log a day to a file, and drops the days
# older than the bound - named, the way every other removal is.
rotate_log() {
  # Not `path`: in zsh that name is the array behind PATH, and assigning it
  # would leave the pass unable to run anything it says it runs.
  local root="$1" file day cutoff legacy own
  mkdir -p "$root/.swarmforge"
  cutoff="$(date -u -r $(( $(date +%s) - KEEP_DAYS * 86400 )) '+%Y-%m-%d')"
  for file in "$root"/.swarmforge/stall-watch-*.log(N); do
    day="${${file:t}#stall-watch-}"
    day="${day%.log}"
    [[ "$day" < "$cutoff" ]] || continue
    rm -f -- "$file"
    say "dropped the schedule's own log $file, older than $KEEP_DAYS days"
  done
  # The one file the pass wrote before the day went into the name, which is the
  # file that grew without limit and put this bound on the pass's own log. Age
  # judges it now, because nothing writes it every minute any more: a forge whose
  # agent still appends to it is left alone, since then its mtime is always now.
  legacy="$root/.swarmforge/stall-watch.log"
  own="$(stat -f %m "$legacy" 2>/dev/null || true)"
  if [[ -n "$own" ]] && (( own < $(date +%s) - KEEP_DAYS * 86400 )); then
    rm -f -- "$legacy"
    say "dropped the schedule's own log $legacy, older than $KEEP_DAYS days"
  fi
}

# prune_projects hands the projects to the layer's own pruner, whose rule - the
# newest twenty scenario runs and the newest mutant run per directory - is
# already decided, and whose reach is already bounded. The pass is the kit's and
# the pruner is the layer's, so a forge that does not carry one is named rather
# than passed over; a walk over every worktree is once a day, gated on the date.
prune_projects() {
  local root="$1" pruner stamp day
  pruner="$root/swarmforge/scripts/$PRUNER"
  stamp="$root/.swarmforge/prune-build-debris.date"
  day="$(date -u '+%Y-%m-%d')"
  if [[ ! -x "$pruner" ]]; then
    say "the projects of $root were not pruned because the pruner is not there ($pruner)"
    return 0
  fi
  [[ -f "$stamp" && "$(cat "$stamp")" == "$day" ]] && return 0
  say "pruned the projects of $root with the layer's pruner: $("$pruner" "$root" 2>&1 | tail -1)"
  printf '%s\n' "$day" > "$stamp"
}

forge_roots() {
  local given=("$@")
  if (( ${#given[@]} == 0 )); then
    given=("$(cd "$SCRIPT_DIR/../.." && pwd)")
  fi
  local root
  for root in "${given[@]}"; do
    root="$(cd "$root" && pwd)"
    [[ -d "$root/projects" ]] || { echo "Not a forge root (no projects/): $root" >&2; exit 2; }
    echo "$root"
  done
}

# A root the schedule cannot serve is a wrong path or a wrong forge rather than
# a quiet one: it holds no project with a roles file to read.
serves_structure() {
  local root="$1" project
  for project in "$root"/projects/*; do
    [[ -f "$project/.swarmforge/roles.tsv" ]] && return 0
  done
  return 1
}

cmd_run() {
  local roots; roots=(${(f)"$(forge_roots "$@")"})
  local failed=0
  # Declared once, outside the loops: in zsh a repeat `local name` inside a loop
  # prints the value it already has, which would put a stray line in the log.
  local root doorbell_out=""

  # The log the pass writes is today's file in the first root - the same root the
  # heartbeat lives in - so everything said below is kept a day to a file.
  schedule_log_file="$(schedule_log "${roots[1]}")"
  mkdir -p "$(dirname "$schedule_log_file")"

  # The watch's pass over every root first: one pass, every project of every
  # forge it was installed for.
  local watch_out=""
  if ! watch_out="$("$SCRIPT_DIR/stall_watch.sh" run "${roots[@]}" 2>&1)"; then
    say "$watch_out"
    say "the watch's pass failed"
    failed=$((failed + 1))
  else
    say "$watch_out"
  fi

  # Then a doorbell pass for each root: a lost request belongs to one forge, and
  # one root that cannot be served must not stop the others.
  for root in "${roots[@]}"; do
    if ! serves_structure "$root"; then
      say "could not serve the forge root $root: it holds no project with a roles file"
      failed=$((failed + 1))
      continue
    fi
    if ! doorbell_out="$("$SCRIPT_DIR/doorbell.sh" "$root" 2>&1)"; then
      say "$doorbell_out"
      say "the doorbell's pass failed for $root"
      failed=$((failed + 1))
    else
      say "$doorbell_out"
    fi
  done

  # Then the forge's own trim, for every root it serves: the scratch nobody has
  # touched, the days of its own log outside the bound, and the projects once a
  # day. A trim that failed is named like any other pass, and the rest still runs.
  for root in "${roots[@]}"; do
    trim_scratch "$root" || { say "the scratch sweep failed for $root"; failed=$((failed + 1)); }
    rotate_log "$root" || { say "the log rotation failed for $root"; failed=$((failed + 1)); }
    prune_projects "$root" || { say "the projects of $root were not pruned: the pruner failed"; failed=$((failed + 1)); }
  done

  local hb="${roots[1]}/.swarmforge/forge-schedule.heartbeat"
  mkdir -p "$(dirname "$hb")"
  printf '%s roots=%s failed=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "${#roots[@]}" "$failed" > "$hb"
  say "the forge schedule ran for ${#roots[@]} forges (${failed} failed)"
  (( failed == 0 )) || return 1
}

case "${1:-}" in
  run) shift; cmd_run "$@" ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
