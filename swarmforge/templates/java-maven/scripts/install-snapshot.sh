#!/usr/bin/env bash
# Build and test this checkout as a development snapshot, install it into the
# operator's shared local Maven repository, then publish it to the host-supplied
# snapshot repository. A registered release uses the separate release process;
# this command refuses a non-SNAPSHOT version so card completion cannot publish
# a release by accident.
set -euo pipefail

bin_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "$bin_dir/.." && pwd)
cd "$project_root"

version=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$version" != *-SNAPSHOT ]]; then
  echo "install-snapshot: $version is not a snapshot" >&2
  exit 1
fi

snapshot_repository="${SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY:-}"
if [[ -z "$snapshot_repository" ]]; then
  echo "install-snapshot: SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY is required" >&2
  exit 1
fi
if [[ ! "$snapshot_repository" =~ ^[^:[:space:]]+::default::[^[:space:]]+$ ]]; then
  echo "install-snapshot: SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY must use <repository-id>::default::<repository-url>" >&2
  exit 1
fi

exec mvn -B -Pwith-release-tests deploy \
  "-DaltSnapshotDeploymentRepository=$snapshot_repository"
