# release-runtime-upgrade-status-lock

# Release the runtime-upgrade status lock on exit

## Operator-observed need

The 2026-11-22 Saibill runtime rollout completed successfully, but a diagnostic
`forge_runtime_upgrade.sh status` process remained after emitting its JSON and
continued to hold Saibill's `upgrade.lock`. Terminating that process released
the lock and let the host verify Saibill as current.

The operator asked for a controlled Saibill upgrade and then for a small
disposable-project acceptance check. A status command that outlives its answer
can make the forge appear locked or `checking` even though the diagnostic has
already reported its result.

## Desired outcome

Make status output, process exit and lock release one observable lifecycle.
Normal status, lock-busy, error and timeout paths must all leave the lock
available after the command exits.

## Plan

1. Reproduce the lifecycle with a focused bounded fixture. Determine whether
   stdout completion can precede process exit or whether a child keeps the
   lock-holding process alive.
2. Make status output, process exit and lock release one lifecycle, with
   assertions for normal, lock-busy, error and timeout paths.
3. Exercise the fix against a disposable idle project and verify repeated
   status reads remain available after each test process exits.

## Boundaries

- Change shared runtime-upgrade tooling only; do not change project source.
- Do not compose, stop or restart an active forge as part of the test.
- Do not use the 2.5-hour mutation workload.
- Preserve existing JSON contracts and the current `current`, `behind`,
  `checking`, `unavailable` and `unknown` status meanings.

## Testing and evidence

- Use a temporary forge fixture and the smallest helper processes needed.
- Bound waits in milliseconds or a few seconds; no production-duration sleeps.
- Assert the child exits, the operation lock can be acquired immediately after
  exit, and an overlapping status reports `checking` until that exit.
- Include an error-path case and verify the lock is released there too.
- Run only the focused runtime-upgrade test targets, not the full test suite or
  another live long-running stage.

## Verification

The fixture proves every status path releases the lock after exit, repeated
status reads do not report a stale lock, and the disposable-project acceptance
check leaves the project idle. No active project or forge runtime is restarted.

The focused runtime-upgrade suite passes nine tests with 85 assertions. The
process-level fixture covers normal completion, overlapping `checking`, source
error and forced timeout, and acquires the lock immediately after each process
exits. Repeated status reads return the normal status instead of `checking`.
No project source or runtime was changed.

After completion, the operator explicitly authorised the canonical `code` and
`clean` gates despite the focused-only testing boundary above. The committed
tree passed `bb test` with 520 tests and 4,154 assertions, zero failures and
zero errors, plus all 20 dashboard browser tests. The `bb crap` clean gate
passed 24 tests with 132 assertions, zero failures and zero errors.

## Operator acceptance

None.
