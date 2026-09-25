#!/usr/bin/env zsh
# A real Synapse homeserver for this machine, pinned to the same version the
# bridge's acceptance fixture uses, in its own directory and virtualenv.
#
#   matrix-homeserver.sh init [--server-name NAME] [--port N] [--bind ADDR]
#                             [--tls-cert FILE --tls-key FILE] [--data-dir DIR]
#   matrix-homeserver.sh start | stop | status | logs | url
#   matrix-homeserver.sh create-user <localpart> [--password PASS] [--admin]
#
# Plain HTTP on 127.0.0.1 is the default, which is enough to run and test
# locally. For the phone, point --server-name at the machine's Tailscale name,
# pass the certificate `tailscale cert` writes, and the client URL becomes
# https://<server-name>:<port>.
set -euo pipefail

SCRIPT_FILE="${(%):-%x}"
DIR="${MATRIX_HOMESERVER_DIR:-$HOME/matrix-homeserver}"
SYNAPSE_VERSION="${MATRIX_SYNAPSE_VERSION:-1.161.0}"

die() { print -u2 -- "matrix-homeserver: $*"; exit 1 }
say() { print -- "$*" }

usage() {
  sed -n '2,12p' "$SCRIPT_FILE" | sed 's/^# \{0,1\}//'
}

config_file() { print -- "$DIR/homeserver.yaml" }
python_bin() { print -- "$DIR/venv/bin/python" }
run_pid_file() { print -- "$DIR/run.pid" }
session_name() { print -- "matrix-homeserver" }
tmux_socket() { print -- "$DIR/tmux.sock" }

# Synapse writes its own pid file (config: pid_file). Trusting the tmux session
# instead is not enough: killing a session can leave the server running, and a
# start that then fails to bind the port looks successful because the orphan
# still answers.
synapse_pid() {
  local file="$DIR/synapse.pid" pid candidate
  if [[ -r "$file" ]]; then
    pid="$(cat "$file" 2>/dev/null)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      print -- "$pid"
      return 0
    fi
  fi
  # Synapse does not always leave a usable pid file, so fall back to the
  # process list — skipping the caffeinate and tmux wrappers that mention the
  # same command line.
  for candidate in $(pgrep -f "synapse\.app\.homeserver.*$(basename "$(config_file)")" 2>/dev/null); do
    case "$(ps -o command= -p "$candidate" 2>/dev/null)" in
      *caffeinate*|*tmux*) continue ;;
      *) print -- "$candidate"; return 0 ;;
    esac
  done
  return 1
}

require_init() {
  [[ -f "$(config_file)" ]] || die "not initialised; run: matrix-homeserver.sh init ..."
}

api_ok() {
  local url="$1"
  curl -sf --max-time 3 "$url/_matrix/client/versions" >/dev/null 2>&1
}

server_name_value() {
  sed -n 's/^server_name: *//p' "$(config_file)" | head -1 | tr -d '"'
}

random_hex() {
  "$(python_bin)" -c 'import secrets; print(secrets.token_hex(32))'
}

client_url() {
  local scheme="http" host="127.0.0.1" port
  port="$(sed -n 's/^ *- port: *\([0-9]*\).*/\1/p' "$(config_file)" | head -1)"
  if grep -q '^tls_certificate_path:' "$(config_file)"; then
    scheme="https"
    host="$(server_name_value)"
  fi
  print -- "${scheme}://${host}:${port}"
}

cmd_init() {
  local server_name="localhost" port="8008" bind="127.0.0.1" cert="" key=""
  while (( $# )); do
    case "$1" in
      --server-name) server_name="$2"; shift 2 ;;
      --port) port="$2"; shift 2 ;;
      --bind) bind="$2"; shift 2 ;;
      --tls-cert) cert="$2"; shift 2 ;;
      --tls-key) key="$2"; shift 2 ;;
      --data-dir) DIR="$2"; shift 2 ;;
      *) die "unknown init option: $1" ;;
    esac
  done
  [[ -f "$(config_file)" ]] && die "$(config_file) already exists; move it aside to re-init"
  if [[ ( -n "$cert" && -z "$key" ) || ( -z "$cert" && -n "$key" ) ]]; then
    die "--tls-cert and --tls-key go together"
  fi
  [[ -z "$cert" || -r "$cert" ]] || die "certificate not readable: $cert"
  [[ -z "$key" || -r "$key" ]] || die "key not readable: $key"

  # A database without its config is state the operator has to decide about; a
  # bare -shm/-wal pair is transient, but leaving it behind to be paired with a
  # fresh database makes SQLite fail with "disk I/O error" on the first open.
  [[ -f "$DIR/homeserver.db" ]] && die "$DIR/homeserver.db exists without $(config_file); move the database aside to re-init"
  local orphan
  for orphan in "$DIR/homeserver.db-shm" "$DIR/homeserver.db-wal"; do
    [[ -e "$orphan" ]] && { mv "$orphan" "$orphan.orphaned-$(date +%s)"; say "moved stale $(basename "$orphan") aside"; }
  done

  mkdir -p "$DIR/media"
  if [[ ! -x "$(python_bin)" ]]; then
    say "creating virtualenv in $DIR/venv"
    python3 -m venv "$DIR/venv"
  fi
  if ! "$(python_bin)" -c 'import synapse' >/dev/null 2>&1; then
    say "installing matrix-synapse==$SYNAPSE_VERSION (pinned)"
    "$(python_bin)" -m pip install --quiet --upgrade pip
    "$(python_bin)" -m pip install --quiet "matrix-synapse==$SYNAPSE_VERSION"
  fi

  local secret macaroon form tls_lines=""
  secret="$(random_hex)"; macaroon="$(random_hex)"; form="$(random_hex)"
  if [[ -n "$cert" ]]; then
    tls_lines="tls_certificate_path: $(cd "$(dirname "$cert")" && pwd)/$(basename "$cert")
tls_private_key_path: $(cd "$(dirname "$key")" && pwd)/$(basename "$key")
public_baseurl: https://${server_name}:${port}
listener_type: http
listener_tls: true"
  else
    tls_lines="listener_type: http
listener_tls: false"
  fi

  cat > "$(config_file)" <<YAML
server_name: "$server_name"
pid_file: $DIR/synapse.pid
listeners:
  - port: $port
    type: $(sed -n 's/^listener_type: //p' <<<"$tls_lines")
    tls: $(sed -n 's/^listener_tls: //p' <<<"$tls_lines")
    bind_addresses: ["$bind"]
    resources:
      - names: [client, federation]
        compress: false
$(sed -n '/^tls_certificate_path:/,$p' <<<"$tls_lines" | grep -v '^listener_')
database:
  name: sqlite3
  args:
    database: $DIR/homeserver.db
log_config: $DIR/log.config
media_store_path: $DIR/media
signing_key_path: $DIR/signing.key
registration_shared_secret: "$secret"
macaroon_secret_key: "$macaroon"
form_secret: "$form"
enable_registration: true
enable_registration_without_verification: true
report_stats: false
trusted_key_servers: []
suppress_key_server_warning: true
YAML
  chmod 600 "$(config_file)"

  cat > "$DIR/log.config" <<YAML
version: 1
formatters:
  precise:
    format: '%(asctime)s - %(name)s - %(levelname)s - %(message)s'
handlers:
  file:
    class: logging.FileHandler
    formatter: precise
    filename: $DIR/homeserver.log
root:
  level: INFO
  handlers: [file]
disable_existing_loggers: false
YAML

  say "initialised $server_name in $DIR"
  say "client URL: $(client_url)"
  say "next: matrix-homeserver.sh start, then create-user"
}

cmd_start() {
  require_init
  local existing
  if existing="$(synapse_pid)"; then
    say "already running (pid $existing)"
    return 0
  fi
  if api_ok "$(client_url)"; then
    die "$(client_url) is answering but no live pid is recorded in $DIR/synapse.pid; stop whatever owns that port first"
  fi
  # A detached tmux session, the way the forge runs its daemons: a plain
  # background process does not outlive the shell that starts it here, and a
  # session is easy to inspect or kill.
  local wrapper=""
  command -v caffeinate >/dev/null 2>&1 && wrapper="caffeinate -ims "
  local inner="exec ${wrapper}'$(python_bin)' -m synapse.app.homeserver --config-path '$(config_file)'"
  tmux -S "$(tmux_socket)" new-session -d -s "$(session_name)" "$inner"
  local url; url="$(client_url)"
  for _ in {1..40}; do
    api_ok "$url" && { say "started: $url"; return 0; }
    sleep 0.5
  done
  die "started but $url did not answer; see $DIR/homeserver.log"
}

cmd_stop() {
  local pid
  if pid="$(synapse_pid)"; then
    kill -TERM "$pid" 2>/dev/null || true
    for _ in {1..30}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
    say "stopped (pid $pid)"
  else
    say "no live pid in $DIR/synapse.pid"
  fi
  tmux -S "$(tmux_socket)" has-session -t "$(session_name)" 2>/dev/null &&
    tmux -S "$(tmux_socket)" kill-session -t "$(session_name)" || true
  rm -f "$(run_pid_file)"
}

cmd_status() {
  require_init
  local url; url="$(client_url)"
  local pid
  if pid="$(synapse_pid)"; then
    if tmux -S "$(tmux_socket)" has-session -t "$(session_name)" 2>/dev/null; then
      print -- "running (pid $pid, tmux session $(session_name))"
    else
      print -- "running (pid $pid) with no tmux session — orphaned; $SCRIPT_FILE stop clears it"
    fi
  else
    print -- "not running"
  fi
  if api_ok "$url"; then
    print -- "client URL: $url"
    curl -s --max-time 3 "$url/_matrix/client/versions" | "$(python_bin)" -c 'import json,sys; print("versions:", ", ".join(json.load(sys.stdin).get("versions", [])))'
  else
    print -- "client URL: $url (not answering)"
  fi
}

cmd_create_user() {
  require_init
  local localpart="${1:-}"; shift || true
  [[ -n "$localpart" ]] || die "usage: create-user <localpart> [--password PASS] [--admin]"
  local password="" admin_flag="--no-admin"
  while (( $# )); do
    case "$1" in
      --password) password="$2"; shift 2 ;;
      --admin) admin_flag="--admin"; shift ;;
      *) die "unknown create-user option: $1" ;;
    esac
  done
  [[ -n "$password" ]] || password="$(random_hex | cut -c1-20)"
  "$DIR/venv/bin/register_new_matrix_user" \
    -c "$(config_file)" -u "$localpart" -p "$password" "$admin_flag" \
    "$(client_url)" >/dev/null
  say "created @${localpart}:$(server_name_value)"
  say "password: $password"
}

case "${1:-}" in
  init) shift; cmd_init "$@" ;;
  start) shift; cmd_start "$@" ;;
  stop) shift; cmd_stop "$@" ;;
  status) shift; cmd_status "$@" ;;
  url) require_init; client_url ;;
  logs) require_init; tail -f "$DIR/homeserver.log" ;;
  create-user) shift; cmd_create_user "$@" ;;
  ""|-h|--help) usage ;;
  *) usage; exit 1 ;;
esac
