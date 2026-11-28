# honor-spec-approval-before-detached-spec-job

# Honor spec approval before launching a detached spec job

## Operator-observed problem

`forgelet-core` launched detached spec job
`stage-job-spec-1795871571428-1623e842` through the MCP stage boundary. The job
exited successfully and was reconciled, but:

- its recorded `input_hash` was `nil`;
- the generated command script contained no stage commands;
- the card remained in the `spec` lane;
- `stage status --json` reported `spec: stale`;
- no spec proposal existed and `spec_approved` was false.

The agent correctly stopped after the bounded spec grant, but the stage
lifecycle had reported a successful spec job without recording an approved
specification. Retrying or restarting the agent cannot repair that state.

## Desired outcome

Make every launch path enforce the same spec-approval policy as `stage propose
spec` and `stage approve` before a detached spec job can run.

For a single-agent project in automatic approval mode:

- reuse an existing approved proposal when its hash matches the current spec;
- otherwise record the normal forge-policy automatic approval for the current
  task note and feature bundle before creating the detached job;
- record a non-nil spec input hash and complete the existing shared
  spec-approval to `code` board transition;
- bind the detached job and its reconciliation to that approved hash;
- preserve the existing approval record and avoid creating duplicate proposals
  when a current approved bundle is launched again.

For operator approval mode:

- launch only when a current approved proposal exists for the active card;
- otherwise fail before creating a job with bounded approval-required guidance;
- do not create or infer an operator approval.

## Shared lifecycle boundary

The fix belongs in the shared `forgelet-swarmforge` stage lifecycle, especially
the detached launch path in `swarmforge/scripts/stage.bb`.

Do not add spec approval to `forgelet-mcp`. MCP remains a scoped adapter and
must not gain route-card, spec or operator approval authority. The Host remains
responsible for grant presentation and privilege expansion, not this stage
policy decision.

Use one shared approval helper for direct and detached spec paths so automatic
mode, operator mode, correction approval and the board transition cannot drift.

## Required behavior

- A zero-hash spec launch is never recorded as successful.
- An automatic-mode launch with no current approval creates the normal
  `forge policy: automatic spec approval` record before launch.
- An automatic-mode launch with an already-current approval is idempotent and
  does not create another proposal.
- A changed feature or task bundle invalidates the old hash and follows the
  configured approval mode before another job can run.
- Operator-mode launch without a current approval returns bounded
  approval-required guidance and leaves the board, proposals and job store
  unchanged.
- A valid operator-approved spec continues to launch once and reconcile once;
  MCP does not gain an approval tool.
- Failure to record automatic approval, record the spec stage, or advance the
  active card prevents the detached job from being created.
- Existing detached-job timeout, cancellation, retry, wake, evidence and
  one-time reconciliation behavior remains intact.

## Tests and evidence

Add focused regressions using disposable fixture roots and sockets:

- automatic mode with no approval records the approved current bundle, records
  a non-nil hash, moves an active `spec` card to `code`, launches once, and
  reconciles to a current `spec` stage;
- a second launch with the same approved bundle reuses the approval and does
  not duplicate proposal or resolution records;
- changed spec content invalidates the old approval and follows automatic mode
  before a new job is created;
- operator mode without approval creates no job and returns the stable
  approval-required outcome;
- operator mode with a current approval launches and reconciles normally;
- a failure to persist approval or advance the board creates no detached job;
- MCP conformance still reports that stage approval is unavailable through
  MCP.

Record the exact helper-test, assertion and dashboard-test counts. Document the
approval-before-detached-launch rule in the layer documentation. Do not restart
or compose the active forge as part of the card.

## Boundaries

- Work only in the `forgelet-swarmforge` checkout on the `forgelet` branch.
- Do not edit the active forge root's shared scripts directly.
- Do not change project grants or project source.
- Do not run `./swarm`, stop, restart or compose the live forge.
- Do not weaken operator, route-card, evidence or reconciliation gates.
- Do not treat an MCP tool call as approval authority.

## Verification

Automatic and operator approval modes are proven through the detached stage-job
path. Successful launches always carry a current non-nil spec hash, invalid
approval cannot produce a no-op job, active cards advance through the existing
shared transition, and the full layer test suite remains green. Source is
committed and pushed on the `forgelet` branch.

## Operator acceptance

None.
