#!/usr/bin/env bash
# Run the approved Gherkin features against this project's public API.
#
#   gherkin-parser features/*.feature -> IR
#   javac acceptance/src              -> target/acceptance/classes
#   AcceptanceRunner <ir>             -> one PASS/FAIL line per scenario
#
# The harness is compiled separately from the project's Maven modules, so it
# works for a single module or a reactor without adding a module to the build.
# Its log is target/acceptance/acceptance.log, the evidence the qa (or verify)
# stage records. A missing step handler, an ambiguous handler, or a failed
# assertion fails this script and therefore the stage.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v gherkin-parser >/dev/null 2>&1; then
  echo "acceptance: gherkin-parser is not on PATH; run swarm_tool.sh require gherkin-parser" >&2
  exit 1
fi

features=()
while IFS= read -r feature; do
  features+=("$feature")
done < <(find features -type f -name '*.feature' 2>/dev/null | sort)

if [[ ${#features[@]} -eq 0 ]]; then
  echo "acceptance: no features/*.feature files; the approved spec has no executable behaviour" >&2
  exit 1
fi

echo "compiling the project and the acceptance harness"
mvn -q test-compile
mvn -q -DincludeScope=test dependency:build-classpath \
  -Dmdep.outputFile=target/acceptance-classpath.txt

classpath=""
while IFS= read -r file; do
  [[ -f "$file" ]] || continue
  classpath="${classpath:+$classpath:}$(<"$file")"
done < <(find . -name acceptance-classpath.txt -type f | sort)

while IFS= read -r dir; do
  classpath="$classpath:$dir"
done < <(find . -type d \
  \( -path '*/target/classes' -o -path '*/target/test-classes' \) | sort)

mkdir -p target/acceptance/classes target/acceptance/ir
find target/acceptance/classes target/acceptance/ir -type f -delete

sources=()
while IFS= read -r source; do
  sources+=("$source")
done < <(find acceptance/src -type f -name '*.java' | sort)
if [[ ${#sources[@]} -eq 0 ]]; then
  echo "acceptance: no Java sources under acceptance/src" >&2
  exit 1
fi
javac -cp "$classpath" -d target/acceptance/classes "${sources[@]}"

report=target/acceptance/acceptance.log
: > "$report"
failed=0
for feature in "${features[@]}"; do
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
  ir="target/acceptance/ir/$stem.json"
  echo "parsing $feature" | tee -a "$report"
  gherkin-parser "$feature" "$ir"
  echo "running $feature" | tee -a "$report"
  if ! java -cp "target/acceptance/classes:$classpath" \
      com.sgo.acceptance.AcceptanceRunner "$ir" | tee -a "$report"; then
    failed=1
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo "acceptance: one or more scenarios failed; see $report" >&2
  exit 1
fi
echo "acceptance: all scenarios passed; see $report"
