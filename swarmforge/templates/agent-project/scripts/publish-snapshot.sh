#!/usr/bin/env bash
# Publish this checkout's development snapshot to its configured Maven
# repository. A release uses the separate release stage; this command refuses
# a non-SNAPSHOT version so a card completion cannot publish a release by
# accident.
set -euo pipefail

bin_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "$bin_dir/.." && pwd)
cd "$project_root"

version=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$version" != *-SNAPSHOT ]]; then
  echo "publish-snapshot: $version is not a snapshot" >&2
  exit 1
fi

GITHUB_PACKAGES_TOKEN=$("$bin_dir/github-packages-token.sh")
export GITHUB_PACKAGES_TOKEN

exec mvn -B -Pwith-release-tests deploy
