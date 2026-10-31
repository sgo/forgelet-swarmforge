#!/usr/bin/env bash
# Install this checkout's development snapshot in the operator's shared Maven
# repository. A registered release uses the separate release process; this
# command refuses a non-SNAPSHOT version so card completion cannot publish a
# release by accident.
set -euo pipefail

bin_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "$bin_dir/.." && pwd)
cd "$project_root"

version=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$version" != *-SNAPSHOT ]]; then
  echo "install-snapshot: $version is not a snapshot" >&2
  exit 1
fi

exec mvn -B -Pwith-release-tests install
