#!/usr/bin/env bash
# Run the current card's declared end-to-end QA procedure. The checker reads
# qa/e2e.edn, validates required inputs, redacts their values and writes
# target/e2e/check.log.
set -euo pipefail

bin_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "$bin_dir/.." && pwd)

exec bb "$project_root/swarmforge/scripts/check-e2e.bb" --root "$project_root"
