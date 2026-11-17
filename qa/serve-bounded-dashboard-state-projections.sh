#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$root"

feature="features/serve-bounded-dashboard-state-projections.feature"
test -f "$feature"
for scenario in \
  "Reuse a fresh automation projection" \
  "Coalesce concurrent snapshot builds" \
  "Invalidate before returning a dashboard write" \
  "Refresh direct workspace changes within the bound" \
  "Fail closed when an authoritative refresh fails" \
  "Keep Family roots isolated" \
  "Bound the Saibill-scale projection"
do
  grep -Fq "Scenario: $scenario" "$feature"
done

resolved_approval_feature="features/fix-project-scoped-resolved-approvals.feature"
test -f "$resolved_approval_feature"
for scenario in \
  "Serve historical superseded endings" \
  "Tag project-level resolved approvals with their owner" \
  "Keep unknown endings closed"
do
  grep -Fq "Scenario: $scenario" "$resolved_approval_feature"
done

bb -e '
(require (quote clojure.test) (quote swarmforge.pack-ui-test))
(let [variables
      [(var swarmforge.pack-ui-test/dashboard-automation-projection-reuses-a-bounded-cached-build)
       (var swarmforge.pack-ui-test/dashboard-cache-coalesces-concurrent-snapshot-builds)
       (var swarmforge.pack-ui-test/dashboard-cache-invalidates-successful-writes-before-return)
       (var swarmforge.pack-ui-test/dashboard-cache-expires-for-external-changes-and-isolates-roots)
       (var swarmforge.pack-ui-test/dashboard-projection-age-cannot-outlive-its-source-snapshot)
       (var swarmforge.pack-ui-test/dashboard-cache-invalidates-one-family-without-crossing-roots)
       (var swarmforge.pack-ui-test/dashboard-automation-projection-enforces-fixed-collection-bounds)
       (var swarmforge.pack-ui-test/dashboard-cache-never-serves-a-stale-success-after-failed-refresh)
       (var swarmforge.pack-ui-test/dashboard-automation-projection-is-bounded-on-a-large-family)
       (var swarmforge.pack-ui-test/dashboard-automation-resolved-approvals-preserve-superseded-in-every-scope)
       (var swarmforge.pack-ui-test/dashboard-automation-resolved-approvals-fail-closed-on-invalid-endings)]
      counters (ref clojure.test/*initial-report-counters*)]
  (binding [clojure.test/*report-counters* counters]
    (clojure.test/test-vars variables)
    (let [{:keys [fail error]} @counters]
      (when (pos? (+ fail error))
        (System/exit (+ fail error))))))
'
