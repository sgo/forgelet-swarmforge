# keep-bridge-out-of-forge-runtime-upgrades

# Keep bridge installation out of forge runtime upgrades

## Operator-observed problem

`forgelet-forge` is idle and has four runtime changes plus two project-template
upgrades available, but its previous runtime upgrade failed in `compose` with
`:compose-digest-mismatch` and recovered.

The cause is still present in `forge_runtime_upgrade.bb`:

- status preview calls the composer with `SWARMFORGE_SKIP_BRIDGE=1`;
- apply calls the composer without that setting;
- apply therefore installs or updates bridge-owned files after calculating the
  expected preview digest;
- the resulting installed digest differs from the preview and is rejected.

The Host process is now independently managed by `forgelet`, so Host survival
is no longer the blocker. The forge-root upgrade helper itself is still not
self-consistent.

## Desired outcome

Status/preview and apply compose exactly the same artifact set for the same
source commit.

With the bridge transitional and its lifecycle moving toward
`forgelet-device`, forge-root upgrades should preserve an existing bridge
installation and exclude bridge build/install from both preview and apply. A
forge-root upgrade must not silently replace bridge artifacts.

## Plan

1. Introduce one explicit bridge-composition policy and use it for both
   `status-core` and `execute-upgrade!`.
2. For this rollout policy, pass `SWARMFORGE_SKIP_BRIDGE=1` to both compositions.
3. Preserve existing bridge files, adapter, config, state and rooms. Do not
   delete or rewrite them during the forge-root upgrade.
4. Keep project-template upgrade checks and the existing runtime manifest
   behavior.
5. Record the bridge policy in status/operation evidence so operators can see
   that bridge replacement was intentionally excluded.

## Testing and evidence

- a disposable forge with existing bridge files reports the same digest from
  status and apply for one source tree;
- apply succeeds without invoking bridge build/install and preserves bridge
  fixture bytes;
- the four intended runtime files and any project-template upgrades apply,
  reopen projects and pass health verification;
- a bridge-policy mismatch is rejected in tests rather than creating a digest
  mismatch after mutation;
- current unrelated upgrade and status-lock tests remain green.

## Rollout

After source is committed and pushed on `origin/forgelet`, compose the corrected
helper into `forgelet-forge`, then run the normal idle-only upgrade.

The managed Host must retain PID/start identity throughout; a new Host process
is not expected. The bridge remains stopped during this rollout.

## Boundaries

- Do not remove the bridge implementation in this card.
- Do not migrate bridge state.
- Do not weaken the runtime digest check.
- Do not silently replace bridge artifacts.
- Do not restart the active Host.

## Verification

Disposable-forge coverage proves that status and apply use the same
`preserve-installed-bridge-v1` policy, reach the same digest, pass
`SWARMFORGE_SKIP_BRIDGE=1` to every composition, and leave the bridge adapter,
project, configuration, state and room fixture bytes unchanged. A composer that
reports a different policy fails with `bridge-policy-mismatch`, before the
installed/preview digest comparison, and preserves the bridge fixture. The
runtime suite passed 11 tests with 114 assertions.

The script suite passed 108 tests with 624 assertions. The complete `bb test`
gate passed 528 tests with 4,257 assertions, all 20 dashboard browser tests and
the bounded-projection E2E; the detached lifecycle E2E completed in 1,482 ms.
The `bb crap` clean gate passed 24 tests with 132 assertions and wrote
`target/coverage/lcov.info`.

The active forge was not upgraded in this authoring checkout. Its normal
idle-only upgrade remains the rollout step after the committed `forgelet`
branch is composed into `forgelet-forge`; the independently managed Host and
bridge were not restarted.

## Operator acceptance

None.
