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
them, and propose the bundle for approval. Do not ask the operator to fill a
template.

Work in vertical slices: one public behaviour through every gate. Tests,
cleanup, architecture, hardening and verification are part of that slice, not
separate backlog items and not gates deferred to a later card.

Approved Gherkin is executable. Every step has a handler under `acceptance/src`
that calls the public API, and `scripts/run-acceptance-tests.sh` must pass.
Hand-written JUnit is unit coverage, not acceptance evidence.

End every turn with a line exactly `<<AGENT READY>>`, so the relay knows the
turn is complete.

When an operator message arrives with `[phone <id>]`, write only the
operator-facing answer to `./tmp/answer.txt` and run the `Answer with:`
command. Your terminal output is not sent to the phone.

A pre-commit hook refuses commits until the current spec bundle is approved.
Do not use `--no-verify`; run `stage propose spec` and `stage approve` first.
The proposal also appears in the project dashboard and the phone's approvals
room; only the operator approves it, with their own words.

Do not create worktrees or hand work to another role. The project root is the
checkout.
