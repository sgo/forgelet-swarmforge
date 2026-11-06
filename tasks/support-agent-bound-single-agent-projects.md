# support-agent-bound-single-agent-projects

# Support agent-bound single-agent projects

## Operator-observed need

The Forgelet app will create single-agent projects inside a Family. Each project
chooses its own agent independently of the Family's Lieutenant agent. Today the
single-agent composer hard-codes Codex, and project startup defaults to Codex,
so a requested Claude, Copilot or Grok project would not remain what the
operator chose.

## Desired behaviour

- `get-agent-project` accepts
  `--agent codex|claude|copilot|grok` when creating a project.
- The default remains `codex` for existing callers that omit the option.
- An unsupported or malformed agent is refused before any project directory is
  created.
- The selected agent is persisted in the single-agent project's `roles.tsv`.
- Starting a single-agent project uses the persisted agent to construct the
  agent command. It must not silently fall back to Codex when another supported
  agent is recorded.
- An explicit runtime override remains available for tests and operator
  recovery, but ordinary project startup is driven by the persisted choice.
- The composer and runtime share one supported-agent definition rather than
  drifting.

## Acceptance

- Fixture projects can be composed with each supported agent and record exactly
  that agent.
- Invalid agent ids and path-like values are refused with no filesystem writes.
- Runtime-start tests prove Codex, Claude, Copilot and Grok each produce the
  matching command and that no missing role silently becomes Codex.
- Existing projects with a Codex role and callers without `--agent` continue to
  work unchanged.
- The layer's `code` and `clean` stages pass on the committed `forgelet` tree.
