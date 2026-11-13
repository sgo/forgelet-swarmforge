# Stages

This repository is the source of the shared Forgelet layer. It is a managed
single-agent project inside `forgelet-forge`, but it is not the deployed forge
runtime.

The mainline target is the `forgelet` branch. Do not work against `master`.

## Isolation rules

- Work only in this checkout (`projects/forgelet-swarmforge`).
- Never edit the active forge root's `swarmforge/scripts/` as an authoring
  shortcut.
- Never run a stop, teardown or restart command against
  `/Users/sgo/forgelet-forge` as part of layer work.
- Tests must use their own fixture roots and sockets.
- Compose the active forge only after the layer change is committed and pushed
  to `forgelet`; composition is a release action, not a test step.

## Gates

The stage list is in `stages.edn`.

- `spec` records the task note under `tasks/`.
- `code` runs `bb test`, the complete layer suite including dashboard
  Playwright tests.
- `clean` runs `bb crap`, the in-process coverage suite and CRAP measurement
  for the Babashka helpers. The gates stay separate because CRAP instruments
  only `swarmforge.coverage-in-process-test`; it does not run the full suite.
  `target/coverage/lcov.info` is the clean-stage evidence.
- `release` is optional and covers an explicit composition release.

The coverage gate preserves helper filenames such as `agent-runner.bb`.
`bb coverage:discover` is the fast regression check that Cloverage can resolve
every Babashka helper before the full coverage run starts. During coverage,
the shim also prevents helper entrypoints from starting while Cloverage loads
their definitions, without omitting any source file from the CRAP report.

## Source inputs and report metadata

Task-note spec hashes omit exactly two evidence sections: `## Verification` and
`## Operator acceptance`. Those sections remain in the task file and
`stage complete` reads them in full for final-report validation and operator
acceptance. All requirement text before them and every other task-note section
still affects freshness.

Stage stamps list the source inputs a gate actually consumes. This layer's
`code` stamp covers `bb.edn`, root launchers, devbox helpers, shared
scripts/templates/roles/constitution, tests and browser-test configuration.
`clean` covers `bb.edn`, stage configuration, Babashka helpers, the coverage
shim and the in-process coverage tests. `README.md`, `docs/` and `tasks/` are
excluded because these gates do not consume them.

An unknown stamp value still widens visibly to the whole tree. A missing stamped
file remains represented as missing, so deleting a real input makes the stage
stale. Commit and completion rules are unchanged: stage completion still
requires every non-optional stage current on the exact committed tree.

## Status input expansion

`stage status --json`, `stage list`, stage checks and completion share one
per-command input cache. A declaration such as `console/src` is expanded from
`console/src`, and a wildcard with a fixed prefix is started from that prefix.
Only a wildcard with no fixed directory prefix, such as
`*/build.gradle.kts`, starts at the project root. Repeated stage declarations
reuse the same resolved file list, file hashes and spec content for that
command. The cache is in memory for one invocation; it cannot leak between
projects or processes.

This ordering is part of freshness semantics. The full worktree tree remains
diagnostic metadata, exact stamps still decide whether a stage is current, and
unknown inputs still widen to `"."`. Rooted expansion changes only where the
same declared glob is evaluated.

Before the change, the Forgelet app checkout (56,482 files excluding `.git`,
about 36 GB) did not finish `stage status --json` within 120 seconds.
Fingerprint expansion alone took 100.8 seconds. The temporary Git index was not
the cause: `git add -A` took about 0.09 seconds and `git write-tree` about 0.02
seconds. Most stage directories were scanned from the project root once per
stage, so identical source declarations repeatedly walked tens of thousands of
unrelated files.

After rooted expansion and per-command reuse, completed cold and warm app runs
took 2.67 and 2.28 seconds on 2026-11-13; a later run under concurrent gate
load took 3.21 seconds. It resolved 648 unique declared files and hashed
4,879,364 bytes; the 3,812 stage-input occurrences were cache lookups after
their first expansion. The largest declared groups were `acceptance` (176
files, 1,146,543 bytes), `desktop` (72 files, 955,419 bytes), `qa` (92 files,
686,674 bytes), `features` (86 files, 466,234 bytes) and `tasks` (90 files,
431,985 bytes). The layer checkout took about 0.14 seconds and the small fixture
about 0.07 seconds. A fixture with 1,000 committed generated files and 200
additional ignored files remains bounded and does not add generated paths to
any declared fingerprint.

## Intentional gate boundaries

This checkout owns a shared script layer, not a product application. The
following gates are deliberately not separate stages:

- No product acceptance gate: there is no product Gherkin or end-to-end
  acceptance contract in this repository. The fixture-based helper, package,
  and dashboard suites in `bb test` are the applicable behavioural evidence.
- No property-test gate: the layer has no property suite or pure stateful domain
  API for which property tests would provide distinct evidence.
- No standalone documentation gate: `check-documentation.bb` enforces
  project-specific doc contracts used by generated products. This repository
  documents the layer itself and has no such contract; inventing one here would
  report evidence that does not describe layer behaviour.
- No mutation gate: the layer has no configured Babashka mutation runner or
  mutation manifest. `bb test` and `bb crap` are the supported gates today.
- No deploy or compose gate in `code` or `clean`: composing the committed,
  pushed layer into the active forge is an explicit optional `release` action.
  It is not part of ordinary layer verification and must never restart the
  active forge implicitly.

Run:

```sh
bb swarmforge/scripts/stage.bb list --root .
```

The `forgelet` branch is the source of truth. Do not create worktrees or hand
work to another role.
