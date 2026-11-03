# repair-layer-gates

# Repair the layer project's own gates

## Operator-observed problem

`forgelet-swarmforge` is now a managed single-agent project in
`forgelet-forge`, but its CRAP gate cannot start. Cloverage resolves the
namespace `agent-runner` to `agent_runner.bb`, while the runtime helper is
named `agent-runner.bb`. Other hyphenated helpers have the same mismatch.

The project should now own and repair its gates instead of the lieutenant
changing layer code directly.

## Desired behaviour

- Reproduce the hyphenated-filename failure with an automated regression.
- Make `bb crap` discover and measure the actual hyphenated `.bb` helpers
  without renaming the runtime files or silently excluding coverage.
- Remove the standalone `bb test` duplicate from the `code` stage if CRAP's
  coverage run already executes the full suite, or keep both only when the
  project records why they are separate gates.
- Make `stage list` describe the repaired gates with commands that the layer
  project actually supports.
- Preserve the existing full test suite and dashboard tests.

## Boundaries

- Work only in `projects/forgelet-swarmforge` on branch `forgelet`.
- Do not edit, stop, restart or tear down `/Users/sgo/forgelet-forge` or any of
  its other open projects.
- Do not rename public helper entry points merely to satisfy the coverage tool.
- A composition into the active forge is a separate release action, not part of
  this card's implementation.

## Acceptance

- The regression fails against the current gate and passes after the fix.
- `bb test` passes.
- `bb crap` runs to a measured CRAP result rather than failing during source
  discovery, and meets the project's threshold.
- The project's stage list exposes commands and evidence that reflect the
  repaired gates.
- The final tree is committed and pushed to `forgelet` without touching the
  active forge runtime.
