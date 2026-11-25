# Agent instructions

This repository is the shared Forgelet layer, owned by the managed
`forgelet-swarmforge` project in `forgelet-forge`. Read `mission.md`,
`STAGES.md` and this project's `stages.edn` before changing the layer.

Work on the `forgelet` branch. Do not target `master`. Keep authoring and tests
inside this checkout; do not edit the active forge root's shared scripts
directly. Never stop, restart or tear down `/Users/sgo/forgelet-forge` as part
of layer work. Release to the active forge only by composing this project after
changes are committed and pushed to `forgelet`.

Do not test the text of prompts with an automated unit or acceptance test.
That includes constitution articles, role prompts, Tool Startup, and generated
instruction files. Prompt wording is not production behavior to pin with
`str/includes?`, Gherkin, or any other automated check.

<!-- forgelet:single-agent-correction-policy:begin -->
## Spec approval

A single-agent project approves its spec automatically by default and proceeds
to implementation without an approval request. To restore explicit first-spec
approval for every single-agent project in a forge, create
`<forge>/swarmforge/local-spec-approval.conf` containing `spec=operator`.
In that explicit mode, corrections to the same unfinished card's task note or
Gherkin remain automatically approved after the first operator approval.
<!-- forgelet:single-agent-correction-policy:end -->

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

<!-- forgelet:typed-clarification-guidance:begin -->
## Operator clarifications

When a decision is genuinely the operator's, ask in project chat:

`pack_dashboard_request.sh clarify-chat clar-<stable-id> ./tmp/question.txt`

Use one stable id for the question and keep it stable across retries. The
question file may contain multiline Markdown. Stop and wait after asking; do not
start unrelated work while the clarification is open. If the answer is
insufficient, create a new clarification that references the answered one with
`--reference clar-<answered-id>` rather than reopening the answered record.
Never use the removed legacy `clarify` command.
<!-- forgelet:typed-clarification-guidance:end -->

<!-- forgelet:operator-chat-guidance:begin -->
## Operator chat

When replying through Forgelet app chat, use the shared `operator-chat` skill
for supported safe Markdown, fenced examples, channel behavior and the rule that
prose is not approval or execution authority.
<!-- forgelet:operator-chat-guidance:end -->
