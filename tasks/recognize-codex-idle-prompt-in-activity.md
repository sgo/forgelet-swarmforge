# recognize-codex-idle-prompt-in-activity

# recognize-codex-idle-prompt-in-activity

# Recognize the Codex idle prompt in Lieutenant activity

## Operator-observed problem

After the Lieutenant finishes a response, the app commonly reports `Activity
unknown`. The live Codex session is actually idle and ends with:

```text
• Working (32s • esc to interrupt)

› Ask Codex to do anything

  DeepSeek-Flash high · ~/forgelet-forge · Trace lieutenant prompt references · ← for agents
```

`activity-idle-pattern` does not recognize `› Ask Codex to do anything`.
`activity-output-state` also checks the working pattern before the idle pattern,
so a historical Working marker retained in the bounded tail can override the
current idle prompt.

## Desired behaviour

- Recognize the Codex empty-composer prompt as an explicit idle state.
- Determine the current state from the latest explicit marker in the bounded
  pane tail: a later idle prompt wins over an earlier Working marker, and a
  later Working indicator wins over an earlier idle prompt.
- Preserve Claude, Copilot, Grok and existing idle prompt patterns.
- A dead/missing session remains `unavailable`; an alive agent with no
  recognized current marker remains `unknown`.
- Derive the safe `status_line` from the same bounded output, without exposing
  paths, sockets, commands or credentials.
- Do not relabel `unknown` as idle.

## Testing

- Exact captured Codex idle tail with a historical Working line returns idle.
- Idle prompt followed by a new Working indicator returns working.
- Working followed by the idle prompt returns idle.
- Existing Claude/Copilot/Grok idle and working patterns remain.
- Query, prompt, token-counter and unrelated transcript lines do not classify
  as activity.
- Dead/non-agent shells remain unavailable; alive unknown output remains
  unknown.
- Status-line normalization remains bounded and non-disclosing.
- Health and `/api/state` performance budgets remain green.
- Run all layer gates and compose from pushed `forgelet`.

## Documentation

Document the recognized Codex idle prompt, latest-marker precedence, and why
unknown remains distinct from idle.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change the host activity schema,
app labels or request-state semantics in this card.
