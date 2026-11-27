# Agent instructions

You are the single agent for this project.

Read `STAGES.md`, `mission.md` and the operator's task note before specifying
work. Read `swarmforge/constitution/articles/engineering.prompt` for the shared
engineering rules. Treat language-specific sections there as inactive until the
project chooses a language.

The project is language-neutral. Its first card must choose the implementation
language, write `swarmforge/language.conf`, establish the language's build,
test, mutation and documentation tooling, and replace every placeholder gate in
`stages.edn` before product implementation. Do not infer Java, Maven, Gradle or
another build system from this scaffold.

The runner puts `.swarmforge/bin` and `swarmforge/scripts` first on `PATH`.
The launch boundary also loads
`${SWARMFORGE_PROJECT_RUNTIME_ENV:-$HOME/.config/forgelet/project-runtime.env}`
and the optional owner-only device credential hook. Caller values win. Never
print or commit credential values.

<!-- forgelet:operator-chat-guidance:begin -->
## Operator chat

When replying through Forgelet app chat, use the shared `operator-chat` skill
for supported safe Markdown, fenced examples, channel behavior and the rule that
prose is not approval or execution authority.
<!-- forgelet:operator-chat-guidance:end -->

<!-- forgelet:single-agent-correction-policy:begin -->
## Spec approval

A single-agent project approves its spec automatically by default and proceeds
to implementation without an approval request. To restore explicit first-spec
approval for every single-agent project in a forge, create
`<forge>/swarmforge/local-spec-approval.conf` containing `spec=operator`.
In that explicit mode, corrections to the same unfinished card's task note or
Gherkin remain automatically approved after the first operator approval.
<!-- forgelet:single-agent-correction-policy:end -->

<!-- forgelet:typed-clarification-guidance:begin -->
## Operator clarifications

When a decision is genuinely the operator's, ask in project chat:

`pack_dashboard_request.sh clarify-chat clar-<stable-id> ./tmp/question.txt`

Use one stable id for the question and keep it stable across retries. Stop and
wait after asking; do not start unrelated work while the clarification is open.
Never use the removed legacy `clarify` command.
<!-- forgelet:typed-clarification-guidance:end -->

Work in vertical slices. Tests, cleanup, architecture, hardening and
verification belong to the card that adds or changes the behavior.

Every new or next-touched task note ends with `## Verification` and
`## Operator acceptance`. Use `None.` when no operator residual exists, or a
bounded pending entry when an operator environment is required.

Single-agent routing keeps at most one active card. Additional approved cards
wait in `queued`. Completing the active card through `stage complete` promotes
the oldest queued card.

A detached stage job returns a durable id. End the turn without polling; the
completion wake returns the terminal result for reconciliation.

End every turn with a line exactly `<<AGENT READY>>`.

When an operator message arrives with `[phone <id>]`, write only the
operator-facing answer to `./tmp/answer.txt` and run its `Answer with:` command.

The pre-commit hook refuses commits until the current spec bundle is approved.
Do not use `--no-verify`.

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

Do not create worktrees or hand work to another role.
