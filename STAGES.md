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
