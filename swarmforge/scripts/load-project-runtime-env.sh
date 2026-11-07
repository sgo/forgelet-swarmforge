#!/usr/bin/env bash
set -euo pipefail

validate_environment_file() {
  local env_file="$1"
  if [[ ! -e "$env_file" ]]; then
    return 0
  fi
  if [[ ! -f "$env_file" || ! -r "$env_file" ]]; then
    echo "load-project-runtime-env: cannot read $env_file" >&2
    exit 1
  fi
  if ! bash -n "$env_file"; then
    echo "load-project-runtime-env: malformed shell syntax in $env_file" >&2
    exit 1
  fi
  line_number=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    trimmed="${line#"${line%%[![:space:]]*}"}"
    [[ -z "$trimmed" || "$trimmed" == \#* ]] && continue
    if [[ ! "$line" =~ ^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*= ]]; then
      echo "load-project-runtime-env: malformed assignment in $env_file:$line_number" >&2
      exit 1
    fi
  done < "$env_file"
}

env_file="${SWARMFORGE_PROJECT_RUNTIME_ENV:-}"
if [[ -z "$env_file" && -n "${HOME:-}" ]]; then
  env_file="$HOME/.config/forgelet/project-runtime.env"
fi
if [[ -n "$env_file" ]]; then
  validate_environment_file "$env_file"
fi

if [[ "${1:-}" == "--check" ]]; then
  exit 0
fi
if [[ $# -ne 1 || -z "$1" ]]; then
  echo "load-project-runtime-env: expected an agent command" >&2
  exit 1
fi

if [[ -n "$env_file" && -e "$env_file" ]]; then
  saved="$(mktemp "${TMPDIR:-/tmp}/forgelet-runtime-env.XXXXXX")"
  cleanup() { rm -f "$saved"; }
  trap cleanup EXIT
  export -p > "$saved"
  set -a
  # The file is assignment-only and syntax-checked above.
  . "$env_file"
  set +a
  eval "$(cat "$saved")"
  cleanup
  trap - EXIT
fi

exec bash -c "$1"
