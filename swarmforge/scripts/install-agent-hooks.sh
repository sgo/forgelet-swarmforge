#!/usr/bin/env bash
set -euo pipefail

root="${1:-$(git rev-parse --show-toplevel)}"
source_hook="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/agent-pre-commit.sh"

[[ -f "$source_hook" ]] || {
  echo "install-agent-hooks: missing $source_hook" >&2
  exit 1
}

mkdir -p "$root/.git/hooks"
target="$root/.git/hooks/pre-commit"
if ! cmp -s "$source_hook" "$target" 2>/dev/null; then
  cp "$source_hook" "$target"
  chmod +x "$target"
fi
