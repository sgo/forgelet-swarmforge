#!/usr/bin/env bash
# Persistent runner adapter for gherkin-mutator. The mutator starts this
# command once per worker and sends newline-delimited JSON jobs on stdin.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f target/acceptance/classpath.txt ]]; then
  echo "acceptance runner is not prepared; run scripts/run-acceptance-tests.sh --prepare" >&2
  exit 1
fi

exec java -cp "target/acceptance/classes:$(<target/acceptance/classpath.txt)" \
  com.sgo.acceptance.RunnerWorker
