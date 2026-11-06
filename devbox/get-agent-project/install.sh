#!/bin/bash
# Install the layer's single-agent project composer as a machine-wide command.
#
# The composer reads its templates and supported-agent definition relative to
# its own directory, so a bare symlink in the command directory is not enough.
# The cache retains those files together; the installed command is a small
# wrapper into the current versioned release.
#
#   install.sh          install or refresh the cache and command
#   install.sh --check  report whether they are current, changing nothing
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
CACHE="${FORGELET_AGENT_PROJECT_CACHE:-$HOME/.local/share/forgelet-swarmforge}"
CURRENT="$CACHE/current"
RELEASES="$CACHE/releases"
BIN="${FORGELET_CMDS_DIR:-$HOME/cmds}"
WRAPPER="$BIN/get-agent-project"
LOCAL_LAYER="${FORGELET_SWARM_FORGE_DIR:-}"
ARCHIVE_URL="${FORGELET_SWARM_FORGE_ARCHIVE_URL:-https://github.com/sgo/forgelet-swarmforge/archive/refs/heads/forgelet.tar.gz}"

if [ -z "$LOCAL_LAYER" ]; then
  beside_installer="$(cd "$DIR/../.." 2>/dev/null && pwd || true)"
  if [ -n "$beside_installer" ] && [ -x "$beside_installer/get-agent-project" ]; then
    LOCAL_LAYER="$beside_installer"
  else
    LOCAL_LAYER="$HOME/forgelet-forge/projects/forgelet-swarmforge"
  fi
fi

required_files=(
  get-agent-project
  convert-pack-project
  swarmforge/scripts/agents.conf
  swarmforge/scripts/check-documentation.bb
  swarmforge/scripts/check-e2e.bb
  swarmforge/scripts/upgrade-agent-project.bb
)

wrapper_text() {
  cat <<'WRAPPER'
#!/bin/sh
set -eu

cache="${FORGELET_AGENT_PROJECT_CACHE:-$HOME/.local/share/forgelet-swarmforge}"
exec "$cache/current/get-agent-project" "$@"
WRAPPER
}

release_complete() {
  local root="$1"
  local file
  [ -d "$root/swarmforge/templates/agent-project" ] || return 1
  for file in "${required_files[@]}"; do
    [ -f "$root/$file" ] || return 1
  done
}

wrapper_current() {
  [ -x "$WRAPPER" ] && wrapper_text | cmp -s - "$WRAPPER"
}

replace_current() {
  if [ "$(uname -s)" = "Darwin" ]; then
    mv -f -h "$1" "$2"
  else
    mv -fT "$1" "$2"
  fi
}

cache_valid() {
  release_complete "$CURRENT" && wrapper_current
}

release_matches() {
  local source="$1"
  local target="$2"
  local file
  diff -qr "$source/swarmforge/templates/agent-project" \
           "$target/swarmforge/templates/agent-project" >/dev/null || return 1
  for file in "${required_files[@]}"; do
    cmp -s "$source/$file" "$target/$file" || return 1
  done
}

cache_matches() {
  local source="$1"
  cache_valid && release_matches "$source" "$CURRENT"
}

if [ "${1:-}" = "--check" ]; then
  if ! cache_valid; then
    echo "get-agent-project: the versioned layer cache or command wrapper is missing - run install.sh" >&2
    exit 1
  fi
  if release_complete "$LOCAL_LAYER" && ! cache_matches "$LOCAL_LAYER"; then
    echo "get-agent-project: the cache is behind $LOCAL_LAYER - run install.sh" >&2
    exit 1
  fi
  echo "get-agent-project: $WRAPPER uses the layer cache at $CURRENT"
  exit 0
fi

source_dir="$LOCAL_LAYER"
download_root=""
staging=""
next_current=""
cleanup() {
  [ -z "$download_root" ] || [ ! -d "$download_root" ] || rm -rf "$download_root"
  [ -z "$staging" ] || [ ! -d "$staging" ] || rm -rf "$staging"
  [ -z "$next_current" ] || rm -f "$next_current"
}
trap cleanup EXIT

if ! release_complete "$source_dir"; then
  download_root="$(mktemp -d "${TMPDIR:-/tmp}/forgelet-agent-project.XXXXXX")"
  curl -fsSL "$ARCHIVE_URL" | tar -xz -C "$download_root"
  source_dir="$(find "$download_root" -mindepth 1 -maxdepth 1 -type d \
                      -name 'forgelet-swarmforge-*' -print -quit)"
  release_complete "$source_dir" ||
    { echo "get-agent-project: downloaded layer is incomplete" >&2; exit 1; }
fi

mkdir -p "$BIN" "$CACHE" "$RELEASES"

wrapper_tmp="$BIN/.get-agent-project.$$"
wrapper_text > "$wrapper_tmp"
chmod 755 "$wrapper_tmp"
mv -f "$wrapper_tmp" "$WRAPPER"

if ! cache_matches "$source_dir"; then
  release_id="$(
    cd "$source_dir"
    {
      for file in "${required_files[@]}"; do shasum -a 256 "$file"; done
      find swarmforge/templates/agent-project -type f -print |
        LC_ALL=C sort |
        while IFS= read -r file; do shasum -a 256 "$file"; done
    } | shasum -a 256 | awk '{print $1}'
  )"
  release="$RELEASES/$release_id"

  if [ -d "$release" ] &&
     { ! release_complete "$release" ||
       ! release_matches "$source_dir" "$release"; }; then
    rm -rf "$release"
  fi

  if [ ! -d "$release" ]; then
    staging="$(mktemp -d "$CACHE/.release.XXXXXX")"
    mkdir -p "$staging/swarmforge/scripts"
    cp -Rp "$source_dir/swarmforge/templates" "$staging/swarmforge/templates"
    for file in "${required_files[@]}"; do
      mkdir -p "$staging/$(dirname "$file")"
      cp -p "$source_dir/$file" "$staging/$file"
    done
    mv "$staging" "$release"
    staging=""
  fi

  next_current="$CACHE/.current.$$"
  ln -s "releases/$release_id" "$next_current"
  replace_current "$next_current" "$CURRENT"
  next_current=""
fi

echo "get-agent-project: $WRAPPER -> $CURRENT"
