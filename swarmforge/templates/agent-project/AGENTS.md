# Agent instructions

You are the single agent for this project.

Read `STAGES.md` first. It defines the specifier workflow and the gates.
Read `mission.md` and the operator's task note before writing the spec.
Read `swarmforge/constitution/articles/engineering.prompt` before setting up or
changing the build. It carries the Java tool, profile, quality, release and
deployment rules.

The runner puts `.swarmforge/bin` and `swarmforge/scripts` first on `PATH`. Do
the same in a manual shell before running a stage.

The operator gives the card. You write the task note and Gherkin, validate
them, and record the spec bundle. It is approved automatically by default, or
proposed for operator approval when the forge opts back into that policy. Do
not ask the operator to fill a template.

<!-- forgelet:single-agent-correction-policy:begin -->
## Spec approval

A single-agent project approves its spec automatically by default and proceeds
to implementation without an approval request. To restore explicit first-spec
approval for every single-agent project in a forge, create
`<forge>/swarmforge/local-spec-approval.conf` containing `spec=operator`.
In that explicit mode, corrections to the same unfinished card's task note or
Gherkin remain automatically approved after the first operator approval.
<!-- forgelet:single-agent-correction-policy:end -->

When the card arrived through routing, the stage tool binds that card's board
name and task ID into the spec proposal and moves that same row through every
gate. Do not create a project-named card to stand in for the routed card. If
more than one routed card is active and the task note cannot identify one, stop
and make the operator choose rather than guessing.

Work in vertical slices: one public behaviour through every gate. Tests,
cleanup, architecture, hardening and verification are part of that slice, not
separate backlog items and not gates deferred to a later card.

Approved Gherkin is executable. Every step has a handler under `acceptance/src`
that calls the public API, and `scripts/run-acceptance-tests.sh` must pass.
Hand-written JUnit is unit coverage, not acceptance evidence.
The harden gate also runs `scripts/run-acceptance-mutation.sh`, which mutates
Gherkin example values and fails on a survivor or infrastructure error.
Write value-driven behaviour as Scenario Outlines with example tables. A plain
scenario has no mutable cells, so it is not acceptance-mutation evidence; a
boolean scenario may stay plain, with that choice recorded in the task note.

In the standard profile, the `e2e` stage is part of QA. Register each card in
`qa/e2e.edn` with its procedure, script and required environment-input names.
Missing inputs fail the stage rather than skipping it. Never commit or print
input values; the stage records only names and redacts values from output. If
no end-to-end procedure applies, put an explicit `## E2E waiver` and reason in
the approved task note.

End every turn with a line exactly `<<AGENT READY>>`, so the relay knows the
turn is complete.

When an operator message arrives with `[phone <id>]`, write only the
operator-facing answer to `./tmp/answer.txt` and run the `Answer with:`
command. Your terminal output is not sent to the phone.

A pre-commit hook refuses commits until the current spec bundle is approved.
Do not use `--no-verify`; run `stage propose spec` and `stage approve` first.
The proposal also appears in the project dashboard and the phone's approvals
room; only the operator approves it, with their own words.

<!-- forgelet:single-agent-commit-policy:begin -->
## Commit working checkpoints

After the current spec is approved, commit the approved spec before
implementation and commit coherent working checkpoints as they are completed.
Do not wait for every gate or for deployment to make your first commit, and do
not defer the whole card to one final pre-deploy commit. Keep each checkpoint
reviewable and free of unrelated or generated files.

The pre-commit hook checks only that the current spec bundle is approved. Stage
evidence is tied to the Git tree, so committing unchanged contents does not
invalidate a stage that has passed. Before deployment, commit and push the final
reviewed tree.
<!-- forgelet:single-agent-commit-policy:end -->

Documentation is part of the slice. Before `stage check docs`, review all three
surfaces: `README.md` for installation, coordinates, quick start and project
status; `docs/manual.md` for detailed public behaviour and extension guidance;
and `ARCHITECTURE.md` for boundaries and structural decisions. Record in the
task note what changed in each or why it did not need to change.

Keep `docs/manual-contract.edn` current. Each section names the public APIs it
owns and at least one executable example, unless it is explicitly conceptual
with a reason. The example command runs against the current tree and must print
the declared text; a removed or changed API therefore makes the documenting
section fail rather than leaving stale prose behind.

Snapshot deployment is part of card completion, not an optional release:
after the reviewed tree is committed and pushed, run `stage check deploy`.
It executes `scripts/publish-snapshot.sh` and deploys the current
`-SNAPSHOT`. When `swarmforge/deploy.conf` names a target, `stage complete`
refuses if `stages.edn` has no required `deploy` stage or if that stage is
stale. Do not use a GitHub Actions workflow as a substitute.

Do not create worktrees or hand work to another role. The project root is the
checkout.
