# teach-app-chat-agents-safe-markdown

# Teach app-chat agents safe Markdown

## Operator-observed problem

Saibill's Lieutenant did not know that fenced Markdown code blocks are supported
in app chat. Lieutenant-only guidance would leave project agents with the same
gap, even though all of them communicate through the same app.

## Decision

Decision (operator, 2026-11-19): put detailed chat guidance in the shared,
discoverable skill layer for all app-chat agents. Role instructions should carry
only a short pointer, not duplicate the complete guidance.

## Desired artifact

Add a shared skill, `swarmforge/skills/operator-chat/SKILL.md`, with a
discriminating description for agents communicating with the operator through
Forgelet chat.

The skill should cover:

- supported safe Markdown and fenced blocks for commands, code, configuration,
  JSON and exact text;
- language identifiers where useful and ordinary explanation outside fences;
- bounded replies rather than dumping files or transcripts;
- the difference between ordinary project chat, typed project clarifications,
  Lieutenant quick replies, approvals and completed-card reports;
- the rule that prose is not approval or execution authority.

Add a short managed pointer to the agent-project `AGENTS.md` template and
Lieutenant guidance that names the shared `operator-chat` skill. Preserve
forge/project-authored instructions and local Lieutenant additions.

## Testing

- A composed forge receives the shared skill and discoverable `.agents/skills`
  link.
- The skill has valid frontmatter, a specific description and no unsupported
  rendering claims.
- New and upgraded agent projects receive one managed pointer to the skill
  without duplicating its body or overwriting local instructions.
- Existing fenced-code desktop/phone tests remain green.
- Composition preserves forge-owned skills.

## E2E

In disposable agent fixtures, load the shared skill in a Lieutenant and a
project agent, ask each for a bounded command example, and prove the answer uses
a fenced block. Render representative responses through existing SafeMarkdown
tests; no new app implementation is required for the renderer.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not put the detailed guidance only in
`lieutenant.prompt`, alter Markdown security policy, add raw HTML/file support,
or overwrite forge/project-local instructions.

## Verification

All app-chat agents can discover one shared source for supported chat formatting
and channel semantics, while role/project instructions contain only concise
pointers.

## Operator acceptance

- owner: operator | environment: installed desktop and iPhone | checks: Lieutenant and project-agent replies with fenced commands render correctly | status: pending
