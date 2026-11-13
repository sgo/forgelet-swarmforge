# profile-stage-status-latency

# Profile and fix stage status latency

## Operator-observed problem

During the rollout verification for `forgelet-app`, `stage status --json`
did not return within roughly 90 seconds after the app board had become empty
and the checkout was clean. The command was stopped so it would not remain a
background diagnostic. Composition and the three `stage.bb` hash comparisons
had already passed.

## Desired outcome

Measure the complete `stage status --json` path and identify where the latency
comes from before changing behavior. The likely expensive phases are:

- `worktree-tree` using a temporary Git index with `git add -A` and
  `git write-tree`;
- source/input expansion for every configured stage;
- per-file fingerprint hashing across the app checkout;
- state, report and JSON serialization.

Determine whether the cost is repository scale, an app path accidentally
included in source/input expansion, generated files, concurrent resource use, or
the shared stage implementation.

## Required evidence

- Record cold and warm wall times for `stage status --json` on an idle app
  checkout with an empty board.
- Instrument each phase with bounded timings and report the largest contributors.
- Count files/hashed bytes and show which top-level paths dominate the work.
- Compare the app behavior with a small fixture project and with the layer
  checkout.
- Determine whether `git status --porcelain` is fast while the temporary-index
  `git add -A` path is slow.
- Preserve the result and reason for any optimization.

## Safe fixes

Only after the evidence identifies a bottleneck, consider one or more of:

- narrow source/input expansion to declared gate inputs;
- cache or reuse a stable Git tree/index identity where the contract permits;
- avoid hashing ignored/generated paths already excluded by the gate contract;
- expose timing and input counts in machine-readable status without changing
  the existing status fields or evidence semantics.

Do not remove freshness checks, skip stages, weaken clean-tree validation, or
ignore files merely to make the command return quickly. Large repositories must
remain honestly invalidated when their declared inputs change.

## Testing

- Reproduce the current latency with a bounded test harness rather than an
  interactive indefinite wait.
- Prove measured improvement or document that the cost is inherent.
- Cover empty, small, large and generated-input fixture projects.
- Preserve `stage list`, `status --json`, freshness, stale evidence, completion
  refusal and unknown-input widening.
- Run the layer and affected app gates plus the update exercise.

## Boundaries

Investigate from the shared layer and the app checkout without modifying active
project work or deleting user data. Do not use a global cache that leaks between
projects. Do not change acceptance/mutation behavior as part of a diagnostic
tooling change.

## Verification

Bounded profiling on the clean, empty-board Forgelet app checkout found the
slow phase in repeated input expansion. Before the fix, `stage status --json`
did not finish within 120 seconds and fingerprinting alone took 100.8 seconds.
The temporary Git index was not material: `git add -A` took about 0.09 seconds
and `git write-tree` about 0.02 seconds. Recursive root-relative globs repeatedly
walked the repository's 56,482 files across ten stages.

The implementation now starts directory and fixed-prefix wildcard expansion at
their narrowest declared directory and reuses resolved inputs, file hashes and
spec content only for the lifetime of one stage command. Freshness still comes
from the exact declared stamps, unknown inputs still widen to the whole tree,
all stage evidence is retained, and no cross-process cache was added.

The same app command completed in 2.67 seconds cold and 2.28 seconds warm, with
a later 3.21-second run under concurrent gate load. It resolved 648 unique
declared files and hashed 4,879,364 bytes instead of repeatedly walking the whole
checkout. The layer checkout completed in about 0.14 seconds and a small fixture
in about 0.07 seconds.

`bb test` passed 432 tests with 2,820 assertions plus all 16 dashboard browser
tests. `bb crap` passed the full coverage/CRAP gate. The affected app unit-test
gate passed 1,006 tests, and its Kover, CRAP and Detekt clean gate passed with
zero Detekt code smells. The app checkout remained clean; no active Forgelet
forge was composed, stopped, restarted or modified.

## Operator acceptance

None. The optimization changes status latency but not the stage freshness or
completion contract.
