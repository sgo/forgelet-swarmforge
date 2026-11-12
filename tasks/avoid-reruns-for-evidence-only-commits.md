# avoid-reruns-for-evidence-only-commits

# Avoid rerunning technical gates after evidence-only commits

## Operator-observed problem

The operator saw a layer card finish its implementation and tests, commit the
documentation, and then rerun the test suite because finalizing the task report
had changed the working tree.

This is over-broad rather than intentionally strict. The shared stage tool now
supports per-stage input stamps, but `forgelet-swarmforge` declares both `code`
and `clean` with `:stamp [:whole-tree]`. Changes to `README.md`, `docs/`, and
`tasks/` therefore invalidate those stages even though `bb test` does not
consume those files as runtime fixtures.

There is a second loop in the task report itself. The approved spec hash covers
the whole task note, so filling `## Verification` or changing
`## Operator acceptance` from its placeholder after running the gates changes
the spec input. Those sections are evidence and completion metadata, not
implementation requirements.

## Desired behaviour

- Compute task-note freshness from the requirement-bearing content of
  `tasks/<active-card>.md`.
- Exclude the `## Verification` and `## Operator acceptance` sections from spec
  proposals and stage freshness comparisons.
- Continue parsing the complete `## Verification` and
  `## Operator acceptance` sections when `stage complete` evaluates the final
  report, pending acceptance and completion status.
- Replace the layer's `code` and `clean` whole-tree stamps with the exact
  repository inputs those gates consume. Include Babashka task configuration,
  helper scripts, unit/browser test sources and other genuine runtime inputs.
- Exclude `README.md`, `docs/`, and `tasks/` from `code` and `clean` when no
  gate consumes them.
- Preserve invalidation for changes that really matter: helper scripts, tests,
  `bb.edn`, stage configuration, generated project contracts, or any feature or
  requirement input consumed by the gate.
- Keep the committed clean-tree requirement and stage completion rules
  unchanged.
- Keep unknown, malformed or missing stamp inputs fail-visible; this work must
  not silently narrow an unresolved input.

## Testing

- Prove a documentation-only change after `code` and `clean` leaves the spec,
  code and clean stages current.
- Prove replacing the task note's `## Verification` and
  `## Operator acceptance` text leaves the spec, code and clean stages current,
  while `stage complete` still reads the complete final report and refuses
  malformed evidence.
- Prove changing the task note before those sections, a feature input, a helper,
  a test or `bb.edn` still makes the appropriate stages stale.
- Cover file deletion and missing-input behavior.
- Prove `stage complete` still refuses an uncommitted or stale tree and still
  requires all non-optional stages to be current.
- Run the complete layer suite and CRAP gate after changing the stage tool and
  layer configuration.

## Documentation

Explain the distinction between source inputs and report metadata in
`STAGES.md`, the stage-tool documentation and the single-agent template.
Projects should declare focused stamps derived from what their gates actually
consume rather than copying `:whole-tree`.

## Boundaries

This is not permission to skip tests after a source, test, script or
configuration change. Do not weaken the clean committed-tree requirement, make
`stage complete` trust prose, or infer that arbitrary Markdown is harmless.
Only the two report sections become metadata, and the layer's gate inputs must
be explicitly justified.

Do not compose, restart or modify the active forge as part of this card.

## Verification

- `bb test`: 429 tests, 2,808 assertions, all dashboard tests.
- `bb crap`: passed with refreshed `target/coverage/lcov.info`.
- Focused fixtures prove report-only and documentation-only commits preserve
  spec/code/clean evidence while requirement, feature, source, test, helper,
  configuration and deletion changes invalidate the appropriate stages.

## Operator acceptance

None.
