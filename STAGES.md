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

Run:

```sh
bb swarmforge/scripts/stage.bb list --root .
```

The `forgelet` branch is the source of truth. Do not create worktrees or hand
work to another role.
