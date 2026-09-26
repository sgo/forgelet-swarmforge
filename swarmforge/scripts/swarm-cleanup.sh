#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: swarm-cleanup.sh <tmux-socket> <window-ids-file> [session ...] [--because <role> <status>]" >&2
  exit 1
fi

TMUX_SOCKET="$1"
WINDOW_IDS_FILE="$2"
TERMINAL_BACKEND="${SWARMFORGE_TERMINAL_BACKEND:-terminal-app}"
WORKING_DIR="$(cd "$(dirname "$WINDOW_IDS_FILE")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
shift
shift

# Why this ran, when the launcher can say: which role's process ended and with
# what status. It is the only actor that ends a project without the watchdog
# being involved, and until this it said nothing at all - so a swarm that ended
# silently could not be told from one killed by something else.
because_role=""
because_status=""
sessions=()
while (( $# )); do
  case "$1" in
    --because)
      because_role="${2:-}"
      because_status="${3:-}"
      shift 3
      ;;
    *)
      sessions+=("$1")
      shift
      ;;
  esac
done

# Everything this cleanup says - its own reason first - goes to the project's
# own log rather than nowhere.
cleanup_log="$WORKING_DIR/.swarmforge/cleanup.log"
mkdir -p "$(dirname "$cleanup_log")"
if [[ -n "$because_role" ]]; then
  printf '%s the %s process exited (status %s) - stopping this project\n' \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$because_role" "$because_status" >> "$cleanup_log"
fi
exec >> "$cleanup_log" 2>&1

has_command() {
  command -v "$1" &>/dev/null
}

source "$SCRIPT_DIR/swarm-terminal-adapter.sh"
load_terminal_backend "$TERMINAL_BACKEND"

if [[ -x "$SCRIPT_DIR/pack_board.sh" ]]; then
  "$SCRIPT_DIR/pack_board.sh" archive-all --root "$WORKING_DIR" || true
fi

if has_command bb; then
  bb "$SCRIPT_DIR/stop_handoff_daemon.bb" "$WORKING_DIR" 2>/dev/null || true
else
  DAEMON_PID_FILE="$WORKING_DIR/.swarmforge/daemon/handoffd.pid"
  if [[ -f "$DAEMON_PID_FILE" ]]; then
    daemon_pid="$(< "$DAEMON_PID_FILE")"
    if [[ "$daemon_pid" == <-> ]]; then
      kill -TERM "$daemon_pid" 2>/dev/null || true
    fi
    rm -f "$DAEMON_PID_FILE"
  fi
fi

for session in "${sessions[@]}"; do
  tmux -S "$TMUX_SOCKET" kill-session -t "$session" 2>/dev/null || true
done

sleep 1

if [[ -f "$WINDOW_IDS_FILE" ]]; then
  while IFS= read -r window_id; do
    [[ -n "$window_id" ]] || continue
    terminal_close_window "$window_id"
  done < "$WINDOW_IDS_FILE"
fi
