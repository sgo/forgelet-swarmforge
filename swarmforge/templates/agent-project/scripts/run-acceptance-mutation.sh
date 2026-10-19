#!/usr/bin/env bash
# Gherkin acceptance mutation: mutate example values in each approved feature
# and confirm the acceptance runner detects every change.
#
# The mutator rewrites the feature with its manifest and stamp, so each
# feature is copied into tmp/ first. The approved feature files stay untouched;
# the gate log records the mutation totals instead.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v gherkin-mutator >/dev/null 2>&1; then
  echo "mutation: gherkin-mutator is not on PATH; run swarm_tool.sh ensure gherkin-mutator" >&2
  exit 1
fi

scripts/run-acceptance-tests.sh --prepare

work=tmp/acceptance-mutation
mkdir -p "$work"
find "$work" -mindepth 1 -delete
mkdir -p "$work/features" target/acceptance-mutation

report=target/acceptance-mutation/mutation.log
: > "$report"
failed=0
ran=0
adapter_args=()
if [[ -x acceptance/value-adapter.sh ]]; then
  adapter_args=(--value-adapter "acceptance/value-adapter.sh")
fi

while IFS= read -r feature; do
  [[ -f "$feature" ]] || continue
  waived=""
  if [[ -f acceptance/waived-features.txt ]]; then
    while IFS=$'\t' read -r path reason; do
      [[ -z "$path" || "$path" == \#* ]] && continue
      if [[ "$path" == "$feature" ]]; then
        waived="${reason:-listed in acceptance/waived-features.txt}"
      fi
    done < acceptance/waived-features.txt
  fi
  if [[ -n "$waived" ]]; then
    echo "waived $feature: $waived" | tee -a "$report"
    continue
  fi

  stem="$(printf '%s' "$feature" \
    | sed -e 's#^features/##' -e 's#/#-#g' -e 's#\.feature$##')"
  copy="$work/features/$stem.feature"
  cp "$feature" "$copy"
  echo "mutating $feature" | tee -a "$report"
  if ! gherkin-mutator \
      --feature "$copy" \
      --work-dir "$work/$stem" \
      --status-interval 15s \
      --level hard \
      --runner-worker "acceptance/runner-worker.sh" \
      "${adapter_args[@]}" 2>&1 | tee -a "$report"; then
    failed=1
  fi
  ran=1
done < <(find features -type f -name '*.feature' 2>/dev/null | sort)

if [[ "$ran" -eq 0 ]]; then
  echo "mutation: no executable features; every feature is waived" | tee -a "$report"
fi

if [[ "$failed" -ne 0 ]]; then
  echo "mutation: Gherkin mutation reported a survivor or error; see $report" >&2
  exit 1
fi
echo "mutation: Gherkin mutation passed; see $report"
