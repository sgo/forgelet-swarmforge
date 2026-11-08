#!/usr/bin/env zsh
# Run the forgelet-bridge service for this forge: the Matrix transport that
# carries a forge's chat channel to the operator's phone.
#
#   matrix-bridge.sh start | stop | status | logs
#   matrix-bridge.sh add-forge <forge-root> <name>
#   matrix-bridge.sh remove-forge <forge-root>
#   matrix-bridge.sh install-rules
#   matrix-bridge.sh install-kit
#
# This is the forge's own copy of the adapter: it works out the forge it serves
# from where the script itself lives, so it is run from the forge rather than
# from the bridge's repository.
#
# Config defaults to <forge-root>/.swarmforge/matrix-bridge.json, which is
# runtime state and never committed. The binary defaults to the project's
# build output; set MATRIX_BRIDGE_BINARY or MATRIX_BRIDGE_CONFIG to override.
#
# The service runs in a detached tmux session under caffeinate, the same way
# the forge runs its handoff daemon, so it outlives the shell that started it
# and keeps the machine from idle sleeping while it is up.
#
# add-forge adds one forge to the configuration: it writes the entry, asks the
# bridge whether the configuration would serve before anything replaces the
# good file, keeps a copy of what it replaced, restarts the bridge, and prints
# the line the bridge logged naming the forge it reached.
#
# remove-forge validates one configured root and its saved room ids, then stops
# the bridge before clearing that forge's Matrix rooms. It leaves the bridge
# stopped, leaves the configuration and forge files alone, and prints one
# sanitized JSON result. A retry resumes from saved per-room progress.
#
# install-rules installs the prompt rules the bridge's rooms rely on into this
# forge's own constitution and the packs its projects are scaffolded from. It
# owns one marked block per rule, so running it again is safe, what drifted is
# refreshed, and the forge keeps every word it wrote itself. A project's own
# tracked copy is never touched: a project picks a rule up through its
# specifier, like any other change.
#
# install-kit installs the tools that make this forge behave - the route gate,
# the idler check and the stall watch - into the forge's own scripts, with the
# watch's agent, and self-checks each one against this forge: one live pane, one
# board row, one inbox. A tool that reads nothing is a failure on the page
# rather than a clean-looking silence. It ships tools, not policy: how strict
# the gate is stays in this forge's own lieutenant prompt, and the report says
# which wording it found and left alone.
set -euo pipefail

SCRIPT_FILE="${(%):-%x}"
FORGE_ROOT="${MATRIX_BRIDGE_FORGE_ROOT:-$(cd "$(dirname "$SCRIPT_FILE")/../.." && pwd)}"
CONFIG="${MATRIX_BRIDGE_CONFIG:-$FORGE_ROOT/.swarmforge/matrix-bridge.json}"
BINARY="${MATRIX_BRIDGE_BINARY:-$FORGE_ROOT/projects/forgelet-bridge/build/acceptance/bin/forgelet-bridge}"
RULES_BINARY="${MATRIX_BRIDGE_RULES_BINARY:-$FORGE_ROOT/projects/forgelet-bridge/build/acceptance/bin/install-rules}"
RULES_DIR="${MATRIX_BRIDGE_RULES:-$FORGE_ROOT/projects/forgelet-bridge/rules}"
KIT_BINARY="${MATRIX_BRIDGE_KIT_BINARY:-$FORGE_ROOT/projects/forgelet-bridge/build/acceptance/bin/install-kit}"
KIT_DIR="${MATRIX_BRIDGE_KIT:-$FORGE_ROOT/projects/forgelet-bridge/swarmforge/scripts}"
SESSION="${MATRIX_BRIDGE_SESSION:-matrix-bridge}"
LOG="$FORGE_ROOT/.swarmforge/matrix-bridge.log"
# The socket sits beside the forge, unless the forge's path is too deep for one
# to fit: a socket path is only about a hundred bytes, and a deep checkout
# would otherwise make tmux refuse to start.
socket_path() {
  local beside="$FORGE_ROOT/.swarmforge/matrix-bridge-tmux.sock"
  if (( ${#beside} <= 96 )); then
    print -- "$beside"
    return
  fi
  print -- "${TMPDIR:-/tmp}matrix-bridge-$(print -rn -- "$FORGE_ROOT" | cksum | cut -d' ' -f1).sock"
}
SOCKET="$(socket_path)"

die() { print -u2 -- "matrix-bridge: $*"; exit 1 }

usage() {
  sed -n '2,40p' "$SCRIPT_FILE" | sed 's/^# \{0,1\}//'
}

running() { tmux -S "$SOCKET" has-session -t "$SESSION" 2>/dev/null }

state_dir() {
  sed -n 's/.*"state_dir": *"\([^"]*\)".*/\1/p' "$CONFIG" | head -1
}

status_file() {
  print -- "$(state_dir)/status.json"
}

cmd_start() {
  [[ -r "$CONFIG" ]] || die "config not readable: $CONFIG"
  [[ -x "$BINARY" ]] || die "binary not found or not executable: $BINARY (build it with the project's scripts/build.sh)"
  if running; then
    print -- "already running (tmux session $SESSION)"
    return 0
  fi
  local wrapper=""
  command -v caffeinate >/dev/null 2>&1 && wrapper="caffeinate -ims "
  # The bridge is started in the forge root, so a configuration that names its
  # state directory relative to the forge keeps it there whatever directory the
  # adapter was called from.
  local inner="cd '$FORGE_ROOT' && exec ${wrapper}'$BINARY' --config '$CONFIG' >> '$LOG' 2>&1"
  tmux -S "$SOCKET" new-session -d -s "$SESSION" "$inner"
  local status_path; status_path="$(status_file)"
  for _ in {1..60}; do
    if [[ -f "$status_path" ]] && grep -q '"tick"' "$status_path" 2>/dev/null; then
      print -- "started (tmux session $SESSION)"
      cmd_status
      return 0
    fi
    running || die "the bridge exited; see $LOG"
    sleep 0.5
  done
  die "started but no status appeared at $status_path; see $LOG"
}

cmd_stop() {
  if running; then
    tmux -S "$SOCKET" kill-session -t "$SESSION"
    print -- "stopped"
  else
    print -- "not running"
  fi
}

cmd_status() {
  local status_path; status_path="$(status_file)"
  if running; then
    print -- "running (tmux session $SESSION)"
  else
    print -- "not running"
  fi
  [[ -f "$status_path" ]] && print -- "status: $(cat "$status_path")"
  [[ -f "$LOG" ]] && print -- "last log: $(tail -1 "$LOG")"
}

# add_entry writes the configuration with one more forge in it.
add_entry() {
  local root="$1" name="$2" candidate="$3"
  python3 - "$CONFIG" "$candidate" "$root" "$name" <<'PY'
import json, sys

with open(sys.argv[1]) as handle:
    config = json.load(handle)
config.setdefault("forges", []).append({"root": sys.argv[3], "name": sys.argv[4]})
with open(sys.argv[2], "w") as handle:
    json.dump(config, handle, indent=2)
    handle.write("\n")
PY
}

# wait_for_forge prints the line the bridge logged naming the forge it reached.
wait_for_forge() {
  local name="$1" status_path; status_path="$(status_file)"
  for _ in {1..300}; do
    if [[ -f "$status_path" ]] && python3 - "$status_path" "$name" <<'PY'
import json, sys

with open(sys.argv[1]) as handle:
    status = json.load(handle)
sys.exit(0 if sys.argv[2] in status.get("reached_forges", []) else 1)
PY
    then
      grep -- "reached" "$LOG" | tail -1 || print -- "the bridge reached $name"
      return 0
    fi
    sleep 0.5
  done
  die "the bridge never reported reaching $name; see $LOG"
}

cmd_install_rules() {
  [[ -x "$RULES_BINARY" ]] || die "installer not found or not executable: $RULES_BINARY (build it with the project's scripts/build.sh)"
  [[ -d "$RULES_DIR" ]] || die "rules not found: $RULES_DIR"
  "$RULES_BINARY" --forge-root "$FORGE_ROOT" --rules "$RULES_DIR"
}

cmd_install_kit() {
  [[ -x "$KIT_BINARY" ]] || die "kit installer not found or not executable: $KIT_BINARY (build it with the project's scripts/build.sh)"
  [[ -d "$KIT_DIR" ]] || die "the tools the kit ships are not found: $KIT_DIR"
  "$KIT_BINARY" --forge-root "$FORGE_ROOT" --kit "$KIT_DIR"
}

cmd_add_forge() {
  local root="${1:-}" name="${2:-}"
  [[ -n "$root" && -n "$name" ]] || die "add-forge needs a forge root and the name the operator knows it by"
  [[ -r "$CONFIG" ]] || die "config not readable: $CONFIG"
  [[ -x "$BINARY" ]] || die "binary not found or not executable: $BINARY (build it with the project's scripts/build.sh)"

  local candidate="$CONFIG.new"
  local checked="$FORGE_ROOT/.swarmforge/matrix-bridge.validate.out"
  add_entry "$root" "$name" "$candidate"
  # The bridge itself says whether the configuration would serve, so an entry
  # that would break it is refused while the good file is still where it was.
  if ! "$BINARY" --config "$candidate" --validate >"$checked" 2>&1; then
    rm -f "$candidate"
    die "refused $name: $(cat "$checked")"
  fi

  local kept="$CONFIG.replaced-$(date -u +%Y%m%dT%H%M%SZ)"
  cp "$CONFIG" "$kept"
  mv "$candidate" "$CONFIG"
  print -- "kept $kept"

  cmd_stop >/dev/null
  cmd_start >/dev/null
  wait_for_forge "$name"
}

cmd_remove_forge() {
  if (( $# != 1 )) || [[ -z "${1:-}" ]]; then
    print -- '{"status":"refused","reason":"unsafe_root"}'
    return 0
  fi
  local root="$1"
  [[ -r "$CONFIG" ]] || {
    print -- '{"status":"refused","reason":"configuration_unavailable"}'
    return 0
  }
  [[ -x "$BINARY" ]] || {
    print -- '{"status":"refused","reason":"configuration_unavailable"}'
    return 0
  }

  local planned
  if ! planned="$(cd "$FORGE_ROOT" && "$BINARY" --config "$CONFIG" --remove-forge-plan "$root")"; then
    print -- '{"status":"retryable","reason":"state_unavailable"}'
    return 0
  fi
  if [[ "$planned" != "ready" ]]; then
    print -- "$planned"
    return 0
  fi

  cmd_stop >/dev/null
  (cd "$FORGE_ROOT" && "$BINARY" --config "$CONFIG" --remove-forge "$root")
}

case "${1:-}" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  logs) [[ -f "$LOG" ]] && tail -f "$LOG" || die "no log at $LOG" ;;
  add-forge) shift; cmd_add_forge "$@" ;;
  remove-forge) shift; cmd_remove_forge "$@" ;;
  install-rules) cmd_install_rules ;;
  install-kit) cmd_install_kit ;;
  ""|-h|--help) usage ;;
  *) usage; exit 1 ;;
esac
