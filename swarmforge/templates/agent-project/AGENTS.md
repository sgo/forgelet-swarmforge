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

End every turn with a line exactly `<<AGENT READY>>`, so the relay knows the
turn is complete.

When an operator message arrives with `[phone <id>]`, write only the
operator-facing answer to `./tmp/answer.txt` and run the `Answer with:`
command. Your terminal output is not sent to the phone.

Do not create worktrees or hand work to another role. The project root is the
checkout.
