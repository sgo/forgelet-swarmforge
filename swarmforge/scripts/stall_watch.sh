#!/usr/bin/env zsh
# Stall watch: run the role-health check on a timer so a role that holds a card
# and goes quiet is noticed without anyone remembering to look.
#
# launchd is the scheduler rather than a long-lived loop of our own. That is the
# answer to "how do we know it is active": the system starts it, restarts it if
# it dies, and reports when it last ran — and each run leaves a heartbeat so a
# silent watcher is visible rather than assumed.
#
# Usage:
#   stall_watch.sh install <forge-root>...   write the launch agent and load it
#   stall_watch.sh status  <forge-root>...   what launchd says, and the heartbeat
#   stall_watch.sh remove                    unload it and delete the plist
#   stall_watch.sh run     <forge-root>...   one pass, the way launchd runs it
#   stall_watch.sh print-agent <forge-root>...  the launch agent it would write
#
# One watch covers every forge root it is installed for: the agent runs the
# forge schedule, which makes the watch's pass over all of them and then a
# doorbell pass for each, and the heartbeat and the log live in the first root,
# which is where the agent was installed from. An alert is raised in the forge
# the stalled role belongs to, and names it.
set -euo pipefail

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LABEL="com.swarmforge.stall-watch"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
INTERVAL=60
STALE_AFTER=180

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

first_forge_root() {
  local given="${1:-}"
  local root="${given:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
  root="$(cd "$root" && pwd)"
  [[ -d "$root/projects" ]] || { echo "Not a forge root (no projects/): $root" >&2; exit 2; }
  echo "$root"
}

heartbeat() { echo "$1/.swarmforge/stall-watch.heartbeat"; }

plist_body() {
  local root="$1"; shift
  local args=""
  local watcher
  for watcher in "$@"; do
    args="$args    <string>$watcher</string>
"
  done
  # No StandardOutPath and no StandardErrorPath: the pass keeps its own log now,
  # a day to a file, because a path launchd opens every minute is one unbounded
  # file however old its contents are. What goes wrong before the pass can write
  # its log is the machine's own log, not a forge file nobody bounds.
  cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/zsh</string>
    <string>$SCRIPT_DIR/forge_schedule.sh</string>
    <string>run</string>
$args  </array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>StartInterval</key><integer>$INTERVAL</integer>
  <key>RunAtLoad</key><true/>
</dict>
</plist>
PLIST
}

cmd_run() {
  local roots; roots=(${(f)"$(forge_roots "$@")"})
  # Declared once, outside the loops: in zsh a repeat `local name` inside a loop
  # prints the value it already has, which would put a stray path in the log on
  # every root after the first.
  local root project
  local checked=0
  for root in "${roots[@]}"; do
    for project in "$root"/projects/*; do
      [[ -f "$project/.swarmforge/roles.tsv" ]] || continue
      "$SCRIPT_DIR/role_health.sh" "$project" --notify || true
      checked=$((checked + 1))
    done
  done
  local hb; hb="$(heartbeat "${roots[1]}")"
  mkdir -p "$(dirname "$hb")"
  printf '%s checked=%s forges=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$checked" "${#roots[@]}" > "$hb"
  echo "checked $checked projects across ${#roots[@]} forges"
}

cmd_install() {
  local roots; roots=(${(f)"$(forge_roots "$@")"})
  local root="${roots[1]}"
  mkdir -p "$(dirname "$PLIST")"
  plist_body "$root" "${roots[@]}" > "$PLIST"
  launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
  launchctl bootstrap "gui/$UID" "$PLIST"
  echo "installed $LABEL every ${INTERVAL}s for ${#roots[@]} forges"
  cmd_status "$@"
}

# print-agent writes the launch agent this install would write, so what the
# machine will run is a thing to read rather than a claim.
cmd_print_agent() {
  local roots; roots=(${(f)"$(forge_roots "$@")"})
  plist_body "${roots[1]}" "${roots[@]}"
}

cmd_status() {
  local root; root="$(first_forge_root "${1:-}")"
  local hb; hb="$(heartbeat "$root")"
  if launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; then
    echo "launchd: loaded ($LABEL)"
    launchctl print "gui/$UID/$LABEL" 2>/dev/null | grep -E "state =|last exit code|runs =" | sed 's/^[[:space:]]*/  /' | head -4
  else
    echo "launchd: NOT loaded ($LABEL)"
  fi
  if [[ -f "$hb" ]]; then
    local age=$(( $(date +%s) - $(stat -f %m "$hb") ))
    echo "heartbeat: $(cat "$hb") — ${age}s ago"
    if (( age > STALE_AFTER )); then
      echo "STALE: no run in ${age}s (want < ${STALE_AFTER}s)"
      echo "the watch has gone quiet"
      return 1
    fi
    echo "the watch is alive"
  else
    echo "heartbeat: none yet ($hb)"
    echo "the watch has gone quiet"
    return 1
  fi
}

cmd_remove() {
  launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
  [[ -f "$PLIST" ]] && mv "$PLIST" "$PLIST.removed"
  echo "removed $LABEL${PLIST:+ (plist moved aside as $PLIST.removed)}"
}

case "${1:-}" in
  install) shift; cmd_install "$@" ;;
  print-agent) shift; cmd_print_agent "$@" ;;
  status)  shift; cmd_status "$@" ;;
  remove)  shift; cmd_remove "$@" ;;
  run)     shift; cmd_run "$@" ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
