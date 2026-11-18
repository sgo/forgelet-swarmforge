# publish-typed-clarification-guidance-to-projects

# Publish typed clarification guidance to projects

## Dependency

Follows `remove-legacy-clarification-store-and-endpoints`, so the final guidance
describes only commands and endpoints that remain.

## Operator-observed problem

Agents have no reliable instruction for the typed clarification mechanism.
The current agent-project template and handoff article mention
`pack_dashboard_request.sh clarify-chat`, but none of the 35 existing project
`AGENTS.md` files contains that guidance. The shared
`stopping-without-finishing.prompt` still tells agents to use the legacy
`clarify` command. Existing project upgrades install spec and commit policy
blocks only, so they never receive newer template instructions.

## Desired behaviour

- Add one managed typed-clarification guidance block to the agent-project
  template:
  - use `pack_dashboard_request.sh clarify-chat clar-<stable-id>
    ./tmp/question.txt` only when a decision is genuinely the operator's;
  - use one stable id per question and multiline Markdown when useful;
  - stop and wait after asking instead of starting unrelated work;
  - when an answer is insufficient, create a new clarification referencing the
    answered one rather than reopening it;
  - never use the removed legacy `clarify` command.
- Teach `get-agent-project --upgrade` to insert or update that managed block in
  existing `AGENTS.md` files without replacing project-specific instructions.
- Update `stopping-without-finishing.prompt` to use `clarify-chat`.
- Keep `handoffs.prompt` aligned and avoid duplicate contradictory wording.
- Make the upgrade idempotent and compatible with existing policy blocks.

## Testing

- New projects receive the managed clarification block.
- Existing projects with old, partial, duplicate or malformed AGENTS guidance
  receive exactly one current block while unrelated text remains unchanged.
- Re-running `--upgrade` is a no-op and `--check` reports missing guidance
  without writing.
- No production prompt instructs agents to use legacy `clarify`.
- Template and upgrade tests cover the exact command, stable id, multiline
  question, stop rule and follow-up reference rule.

## E2E

Create one new project and upgrade one existing project. Start each agent and
confirm its `AGENTS.md` contains one typed-clarification block with
`clarify-chat`, no legacy `clarify`, and preserves project-authored instructions.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not alter clarification runtime
behavior, install the guidance directly into project files from this card, or
overwrite custom agent instructions.

## Verification

- `bb test`: 485 tests, 3,577 assertions, 0 failures, 0 errors; all 20 browser
  tests and the required bounded-projection E2E passed.
- `bb crap`: 24 coverage tests and 132 assertions passed; the complete coverage
  and CRAP gate passed.
- A new project received the managed clarification block from the template.
- Isolated existing-project upgrades covered missing, stale, partial, duplicate,
  end-marker-only and unmanaged-legacy-command AGENTS variants. Each produced
  exactly one current managed block, preserved a project-only marker, reported
  the missing guidance through `--check`, and became a byte-for-byte no-op on the
  next upgrade.
- Production prompt source contains no legacy `clarify` instruction. The
  stopping article and handoff article use `clarify-chat`; the stopping article
  is now a shared composition article rather than a pack-local override.
- Prompt wording was reviewed directly rather than pinned by automated tests, as
  required by this repository's AGENTS instructions.
- No project files were installed directly from this card and no active forge
  was stopped, restarted or composed.

## Operator acceptance

- owner: operator | environment: upgraded project agents | checks: inspect a newly created and an upgraded project, then have one agent raise a harmless typed clarification using the documented command | status: pending
