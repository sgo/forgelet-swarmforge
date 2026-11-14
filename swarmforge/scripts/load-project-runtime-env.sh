#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   load-project-runtime-env.sh --check
#   load-project-runtime-env.sh "<agent command>"
#
# The optional $HOME/.config/forgelet/agent-credentials.sh hook is sourced into
# the final shell. GH_TOKEN is available to every project; the distinct
# GITHUB_PACKAGES_TOKEN is available only for Maven/package-registry projects.

credential_error() {
  echo "load-project-runtime-env: $1" >&2
  exit 1
}

project_needs_packages_token() {
  [[ -f "$PWD/pom.xml" || -d "$PWD/.mvn" ]] && return 0
  local registry="$PWD/swarmforge/package-registry.conf"
  [[ -f "$registry" ]] &&
    grep -Eq '^[[:space:]]*github-packages([[:space:]]|$)' "$registry"
}

validate_credential_hook() {
  local hook="$1"
  [[ -L "$hook" ]] && credential_error "credential hook must not be a symbolic link"
  [[ -f "$hook" && -r "$hook" ]] || credential_error "cannot read credential hook"

  local mode owner current_uid
  mode="$(stat -f '%Lp' "$hook" 2>/dev/null || stat -c '%a' "$hook" 2>/dev/null)" ||
    credential_error "cannot inspect credential hook permissions"
  if (( (8#$mode & 077) != 0 )); then
    credential_error "credential hook must be owner-only"
  fi
  owner="$(stat -f '%u' "$hook" 2>/dev/null || stat -c '%u' "$hook" 2>/dev/null)" ||
    credential_error "cannot inspect credential hook ownership"
  current_uid="$(id -u)"
  [[ "$owner" == "$current_uid" ]] ||
    credential_error "credential hook must be owned by the current user"
  bash -n "$hook" ||
    credential_error "malformed shell syntax in credential hook"
}

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

credential_hook="${HOME:-}/.config/forgelet/agent-credentials.sh"
credential_hook_present=false
if [[ -n "${HOME:-}" && ( -e "$credential_hook" || -L "$credential_hook" ) ]]; then
  credential_hook_present=true
  validate_credential_hook "$credential_hook"
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

if [[ -z "${TERM:-}" || "${TERM}" == "dumb" ]]; then
  export TERM="${SWARMFORGE_TMUX_TERM:-tmux-256color}"
fi
unset NO_COLOR

gh_token_was_set="${GH_TOKEN+x}"
gh_token_before="${GH_TOKEN-}"
packages_token_was_set="${GITHUB_PACKAGES_TOKEN+x}"
packages_token_before="${GITHUB_PACKAGES_TOKEN-}"

if $credential_hook_present; then
  if project_needs_packages_token; then
    export SWARMFORGE_PROJECT_NEEDS_PACKAGES_TOKEN=1
  else
    unset SWARMFORGE_PROJECT_NEEDS_PACKAGES_TOKEN
  fi
  # Source into this shell so exports survive the final exec. Output is
  # suppressed so a hook cannot accidentally disclose a credential.
  if ! source "$credential_hook" >/dev/null 2>&1; then
    unset SWARMFORGE_PROJECT_NEEDS_PACKAGES_TOKEN
    credential_error "credential hook failed to load required GitHub credentials"
  fi
  unset SWARMFORGE_PROJECT_NEEDS_PACKAGES_TOKEN
  { set +x; } 2>/dev/null
fi

# A caller's explicit value wins over the device hook.
if [[ -n "$gh_token_was_set" ]]; then
  export GH_TOKEN="$gh_token_before"
fi
if [[ -n "$packages_token_was_set" ]]; then
  export GITHUB_PACKAGES_TOKEN="$packages_token_before"
fi

if project_needs_packages_token; then
  :
else
  unset GITHUB_PACKAGES_TOKEN
fi

if $credential_hook_present; then
  [[ -n "${GH_TOKEN:-}" ]] ||
    credential_error "credential hook did not load GH_TOKEN"
  if project_needs_packages_token; then
    [[ -n "${GITHUB_PACKAGES_TOKEN:-}" ]] ||
      credential_error "credential hook did not load GITHUB_PACKAGES_TOKEN for this package-registry project"
  fi
fi

if [[ -n "${GH_TOKEN:-}" ]]; then
  command -v gh >/dev/null 2>&1 ||
    credential_error "gh is unavailable while GH_TOKEN is set"
  if ! GH_TOKEN="$GH_TOKEN" command gh auth setup-git --hostname github.com >/dev/null 2>&1; then
    credential_error "gh could not configure Git authentication for GH_TOKEN"
  fi
  if ! GH_TOKEN="$GH_TOKEN" command gh auth status --hostname github.com >/dev/null 2>&1; then
    credential_error "gh could not verify GitHub access for GH_TOKEN"
  fi
fi

exec bash -c "$1"
