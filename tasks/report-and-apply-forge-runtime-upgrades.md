# report-and-apply-forge-runtime-upgrades

# Report and apply forge runtime upgrades

## Operator-observed need

The app should show when a Family is running behind the published Forgelet
runtime and, while that Family is idle, offer an upgrade action. The operator
wants one operation that upgrades consistently instead of manually composing,
upgrading projects, restarting, and repairing consequences.

## Desired behavior

Add a shared-layer, machine-readable upgrade contract that can be called by the
host for any managed forge.

### Status

- Report `current`, `behind`, `checking`, `unavailable` or `unknown`.
- Identify the installed and available composition by a content digest plus the
  canonical source commit. The digest covers composed shared files and excludes
  runtime state, forge-local additions and documented accepted differences.
- Report whether every open project is idle, with bounded blockers naming active
  projects/cards.
- Report the high-level changes the upgrade would apply, including whether
  project template upgrades are required.
- Never expose credentials, paths to clients, arbitrary file contents or an
  unbounded diff.

### Apply

- Recheck idleness and digest immediately before execution.
- Serialize upgrades with a host-scoped lock.
- Stop only the target Family's runtime, preserve boards, requests, worktrees,
  credentials and local untracked files, compose the committed published layer,
  run supported single-agent project upgrades, reopen the original project set,
  and verify health plus the expected installed digest.
- If an upgrade cannot safely continue, stop before mutation or report the exact
  bounded failed phase. A partial failure must remain retryable.
- Do not restart unrelated Families and do not alter Tailscale Serve.
- Provide stable JSON output and nonzero status for preflight, execution and
  verification failures.

## Implementation shape

- Put the reusable check/apply contract under `projects/forgelet-swarmforge`.
- Treat a generated composition manifest as authoritative for local identity;
  do not infer currency from timestamps or a display version.
- Keep upgrade state and operation output outside project worktrees.
- Expose commands suitable for the host's `CommandRunner`; do not make the app
  or host reconstruct compose or project-upgrade commands.

## Testing

- Fixtures for current, behind, unknown, malformed manifest and accepted-local
  difference states.
- A behind fixture plans the exact supported shared/project changes without
  touching the source.
- Busy and changed-idle-state races refuse before mutation.
- Apply uses disposable forge/project roots, preserves state and untracked local
  files, upgrades only the target Family, and verifies the post-upgrade digest.
- Failure at each phase is bounded and retry-safe.
- Tailscale routes are untouched.

## E2E

Compose a disposable forge at an older accepted layer, start two projects,
prove status is behind and busy, make them idle, apply the upgrade, and verify
the original open set is healthy, project state survives, and the installed
digest equals the published target.

## Boundary

Do not implement app rendering or host HTTP routes in this card. Do not add a
second project restore path, update an active project, read or print
credentials, change Tailscale, or claim success from local health alone.

## Verification

The shared command distinguishes current and behind Families, refuses active
work, and upgrades a disposable idle forge to the expected digest while
preserving its state and reopening its original projects.

## Operator acceptance

None.
