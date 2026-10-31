#!/usr/bin/env bash
# Print the classic GitHub Packages token the release publishes with.
# The devbox exports GITHUB_PACKAGES_TOKEN in a login shell. This helper
# deliberately does not fall back to `gh auth token`: GH_TOKEN is a
# fine-grained token, and that token type cannot read GitHub Packages.
set -euo pipefail

token="${GITHUB_PACKAGES_TOKEN:-}"
if [ -z "$token" ]; then
  echo "github-packages-token: GITHUB_PACKAGES_TOKEN is not set" >&2
  exit 1
fi

export GITHUB_PACKAGES_TOKEN="$token"
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  printf '%s\n' "$token"
fi
