# restart-role-replaces-live-tui-agents

# Replace live TUI agents safely on role restart

## Operator-observed problem

While repairing an idle Innersource Lieutenant that carried a stale launch
command, `swarmforge.sh --restart-role` reported the agent gone and typed the
credential-loader launch command into the live Codex UI instead of replacing
its process.

The live Codex child still existed, but its enclosing tmux pane presented a
shell-like current command. The restart path therefore confused a live TUI with
an empty shell. The typed launch command remained in the agent input until it
was manually cleared, and the stale process continued running.

## Desired behavior

- A requested role restart must target the exact role in the exact forge root.
- A live interactive agent must not be classified as dead merely because the
  pane's current command or shell ancestry looks shell-like.
- When the target role is safely idle, replace only that role's agent session
  with a process launched through the current credential-loader boundary.
- If the role cannot be proven restartable, fail visibly without typing a
  command into the old agent, killing unrelated sessions, or starting a second
  agent.
- Preserve other forge roles, project runtimes, open-project state, prompts,
  terminal settings, credentials, and cleanup behavior.
- A successful replacement must have a new process identity tied to the
  intended tmux session and credential-loader launch path.

## Implementation direction

- Replace the current pane-command-only death heuristic with evidence based on
  the session's live process tree and pane identity.
- Do not infer process death from output text or from `pane_current_command`
  alone when a live interactive child is present.
- Make restart a true replacement operation for the target pane/session rather
  than sending launch text into whatever UI happens to be running.
- Keep ordinary dead-shell recovery available when no live agent child exists.

## Testing

- A fixture with a shell pane and a live interactive child is not classified as
  a dead agent.
- Restarting a genuinely dead shell starts the role through the credential
  loader.
- Restarting a live idle TUI replaces only that role and never sends launch text
  into the old agent input.
- Ambiguous process ownership fails closed without modifying the session.
- A second role, a project runtime and an unrelated tmux session remain alive
  with unchanged process identities.
- The replacement child has `GH_TOKEN` presence without printing its value, and
  a non-package forge does not receive `GITHUB_PACKAGES_TOKEN`.
- Malformed prompts, invalid credential hooks, missing sessions and unknown
  roles retain bounded failure behavior.

## E2E

Use a disposable forge with two roles. Put one role in a live TUI and another in
a dead shell. Restart only the target role, prove the live agent is replaced or
refused as specified, and prove the unrelated role stays untouched. Then repeat
with the dead shell and verify credential-aware startup.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not restart the active forge as a
test, edit project runtimes directly, widen credentials, expose token values, or
change project-agent launch semantics beyond the shared role lifecycle needed
for the fix.

## Verification

Focused lifecycle tests and the disposable multi-role E2E prove that restarting
cannot type a launch command into a live agent and cannot leave the stale target
process running after a successful replacement.

## Operator acceptance

- owner: operator | environment: live forge Lieutenant | checks: restart an idle Lieutenant through the corrected lifecycle, confirm the replacement uses the credential loader, and confirm no launch text appears in the old prompt | status: pending
