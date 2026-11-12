# prove-deepseek-harness-acp-runner-boundary

# prove-deepseek-harness-acp-runner-boundary

# Prove DeepSeek Harness ACP as a Forgelet runner boundary

## Operator-observed need

The operator asked to try the DeepSeek Harness integration after the completed
headless smoke test. Headless proved model access, tool execution and persisted
session output, but its one-shot process model has no live approval channel and
does not expose per-turn effort through the CLI. It is not the resident runner
boundary the forge needs.

DSH's shipped ACP profile is the candidate integration surface. It provides a
persistent, multi-session stdio server with semantic updates and per-session
model and reasoning-effort configuration. This card should prove that boundary
with a bounded, tested adapter and live evidence before any production runner
is switched.

## Desired outcome

Add a layer-owned ACP client adapter or probe that can start the pinned
`dsh --profile acp` process and demonstrate the lifecycle Forgelet needs:

- initialise ACP and discover the advertised model and reasoning-effort
  options;
- create, list, resume and close independent sessions in a disposable
  workspace;
- submit bounded prompts and translate committed assistant text, thoughts,
  tool lifecycle and context usage into a small runner-facing event model;
- set the model and reasoning effort explicitly before stage turns and prove
  that an admitted prompt pins its selection;
- answer permission requests through an explicit client callback and test
  allow-once, reject, cancellation and transport failure;
- cancel an in-flight prompt without losing durable history;
- terminate the ACP process while idle, start a new process and resume the same
  persisted session;
- perform a real commit in a disposable Git repository under
  `workspace-write`, without requiring unrestricted host access.

The result should make clear whether ACP can become the next runner boundary.
It is a proof, not an instruction to replace Codex or alter active projects.

## Implementation constraints

- Keep normal tests hermetic. Use a fake ACP peer for protocol, failure,
  permission and lifecycle coverage; gate any live DeepSeek request behind an
  explicit environment flag.
- Use the installed pinned DSH release and its documented ACP profile. Do not
  install `latest`, use `npx`, add third-party plugins or modify the pinned
  release in place.
- Give the probe an explicit temporary `DSH_HOME`. Reuse
  `DEEPSEEK_API_KEY` from the launch environment and never print or commit its
  value.
- Capture stdout as ACP JSON-RPC and keep diagnostics on stderr. Surface
  malformed frames, handshake refusal, protocol errors, process exit and
  transport loss as bounded failures rather than hangs.
- Resolve model and effort option identifiers from ACP's advertised
  configuration. Do not hard-code `medium`, assume an effort level exists, or
  issue presentation-style commands into a pseudo-terminal.
- Preserve one prompt in flight per session, concurrent independent sessions,
  ordered updates and quiescent close semantics.
- Treat ACP permission requests as approvals: allow only one operation, reject
  by default when no handler is configured, and never silently choose
  `danger-full-access`.
- Do not modify the app, host, bridge or active forge. Do not stop, restart,
  compose or tear down `/Users/sgo/forgelet-forge`.

## Live trial sequence

1. Start ACP against a fresh disposable `DSH_HOME` and a disposable normal Git
   checkout whose `.git` directory is inside the workspace.
2. Create one session, select the route explicitly, choose the lowest advertised
   effort for a deterministic read-only task, submit it and record semantic
   updates, duration and token usage.
3. Change the same session to the highest advertised effort and run one bounded
   implementation-shaped task. Record the same evidence and compare outcome,
   retries and time.
4. Exercise one tool call that requests permission. Prove the client receives
   the request and controls the outcome.
5. Close or terminate the ACP process after idle, relaunch it, resume the
   session and prove prior history and stage-relevant state remain visible
   without replaying old updates.
6. In the disposable repository, make one small file change, commit it under
   `workspace-write`, and verify `git status` is clean and the commit object
   exists.

Any live model request must be bounded and must not receive repository
credentials or unrelated host secrets.

## Testing

- Hermetic fixture tests cover initialise, session create/list/resume/close,
  semantic update ordering, low/high effort selection, prompt pinning,
  permission allow/reject/cancel, prompt cancellation, process exit, malformed
  protocol traffic and transport loss.
- Unit tests prove no secret value reaches output, logs, test fixtures or
  committed files.
- A separately invoked live probe records the exact DSH version, advertised
  options, session ids, commands, elapsed time, token usage and terminal
  reasons. Record the evidence under a git-ignored path and summarise durable
  conclusions in documentation that contains no credentials.
- The existing layer, coverage/CRAP and update-exercise gates remain green.

## Documentation

Document the ACP session lifecycle, event mapping, permission boundary,
effort-selection policy, process/resume behavior, Git workspace result and
known DSH preview limitations. Include the exact rollback boundary: leaving the
current runner untouched means rollback is stopping and removing the trial
entry point without migrating project state.

## Verification

Automated gates and the bounded live probe demonstrate the lifecycle, activity,
permission, effort, resume and Git-workspace claims above. Report any ACP
limitation that would prevent adoption instead of hiding it behind a green
fixture test.

## Operator acceptance

None unless the live trial requires a human permission decision or additional
host access.
