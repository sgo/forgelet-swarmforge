# emit-structured-stage-guidance

# Emit structured stage guidance for MCP

## Operator-observed need

`stage.bb` contains important corrective guidance for invalid, incomplete or
gate-skipping requests. The MCP adapter currently recognizes only a few text
substrings and collapses most other failures to `STAGE_HELPER_FAILED`.

Before projects lose their local stage helpers, those guardrails must remain
available through a stable machine-readable contract that MCP can present to
the agent.

## Desired outcome

The shared stage helper emits bounded structured outcomes for success, blocked
gates, approval problems, invalid card selection, job conflicts, stale state,
timeouts, cancellation, failed semantic validation and other actionable
errors, while retaining human-readable CLI output.

## Plan

1. Inventory the existing `stage.bb` exit paths and assign stable error codes
   plus remediation text.
2. Add a machine-readable error envelope, versioned and emitted on a distinct
   stream or prefix so MCP does not need fuzzy matching. Include safe project,
   card, stage, active job id, missing prerequisites and current status.
3. Preserve the existing human prose and exit codes for direct/admin CLI use.
4. Add fixtures for every guardrail, including malformed input, no/multiple
   active cards, blocked predecessors, missing/changed/unapproved spec, active
   or orphan jobs, dirty tree, missing evidence, timeout, cancellation and
   retry restrictions.
5. Do not remove project-local stage executables in this card.

## Boundaries

- Do not expose secrets, absolute private paths or raw process output.
- Do not weaken any gate or approval in order to produce a cleaner error.
- Do not make JSON output depend on a new MCP-only setup; direct CLI must remain
  usable during migration.
- Do not implement the eventual per-project migration.
- Do not use a generic success/failure code where the agent needs a specific
  corrective action.

## Testing and evidence

- Each guardrail emits a stable code, bounded reason and remediation.
- Human CLI output remains unchanged or improves without losing instructions.
- Invalid/malformed machine output fails closed.
- Existing stage and detached-job lifecycle tests remain green.
- A transport-neutral fixture proves an MCP parser can consume every error
  without parsing arbitrary prose.

## Verification

Every stage refusal has a stable structured outcome and actionable guidance.
There is no gate-skipping change, while both existing CLI users and the future
MCP consumer can interpret the same result.

The transport-neutral fixture parses one versioned prefixed envelope, validates
its required fields, rejects malformed JSON and proves stable codes for every
guardrail family without matching human prose. The existing human diagnostic
and exit code remain alongside the envelope.

## Operator acceptance

None.
