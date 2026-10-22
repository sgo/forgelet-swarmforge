#!/usr/bin/env bash
# Run the executable documentation contract and retain its evidence.
set -euo pipefail

bin_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "$bin_dir/.." && pwd)
report="$project_root/target/documentation/check.log"
mkdir -p "$(dirname "$report")"

set -o pipefail
bb "$project_root/swarmforge/scripts/check-documentation.bb" \
  --root "$project_root" 2>&1 | tee "$report"
