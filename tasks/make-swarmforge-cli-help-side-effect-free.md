# make-swarmforge-cli-help-side-effect-free

# Make lifecycle helper help and option errors side-effect free

## Operator-observed problem

Running `swarmforge/scripts/swarmforge.sh --help` from the forge root created
an untracked `--help/` Git repository before failing with:

```text
Config not found at /Users/sgo/forgelet-forge/--help/swarmforge/swarmforge.conf
```

The command should have shown usage. A disposable reproduction exited `1` and
left `.git` behind. The top-level dispatch in `swarmforge.bb` has no `-h` or
`--help` branch, and its fallback treats the first unrecognized argument as a
root. `run-main!` then initializes Git before configuration validation.

## Desired outcome

Top-level help is a pure command-line operation:

- `swarmforge.sh -h` and `swarmforge.sh --help` print the supported lifecycle
  operations and exit `0`.
- Neither form creates a repository, `.swarmforge` state, tmux session,
  dashboard, directory named after the option or any other filesystem/runtime
  side effect.
- An unknown leading option exits nonzero with bounded usage and also performs
  no filesystem or runtime mutation.
- Missing root/project arguments for existing operations are rejected before
  mutation and name the required arguments.

## Plan

1. Add a single top-level usage renderer and dispatch `-h` and `--help` to it.
2. Reject unrecognized leading `-*` tokens before dependency checks, context
   creation or `run-main!`.
3. Keep root/context preparation after command argument validation for every
   operation that can mutate filesystem or runtime state.
4. Add focused tests from an empty disposable working directory covering help,
   an unknown option and an existing operation with a missing argument. Assert
   exit behavior, usage content and an unchanged directory tree.
5. Retain coverage for bare root start, `--restart-forge`, `--open-project`,
   `--close-project`, `--health` and `--health-project`.

## Testing and evidence

- Help commands exit successfully and name the lifecycle operations.
- Each malformed option/argument exits nonzero with usage.
- The empty fixture tree remains byte-for-byte unchanged, including no `.git`
  or option-named directory.
- Existing lifecycle tests pass, including an open-project missing-argument
  check and an already-running forge's refusal of a bare restart.

## Boundaries

- Do not run a bare start against the active forge.
- Do not restart, stop or tear down the active forge as part of source work.
- Do not weaken the existing refusal to restart a running forge with a bare
  invocation.
- Do not change project lifecycle semantics or dashboard behavior.
- Do not add a broader command-parser dependency for this bounded correction.

## Verification

The real `swarmforge.sh` wrapper now prints one lifecycle usage document for
`-h` and `--help`, exiting 0 before dependency checks or context creation.
Unknown leading options and missing required arguments exit 2 with bounded
usage before any filesystem or runtime mutation.

Focused tests ran from empty disposable directories and verified that both help
forms, an unknown option, an open-project missing-argument call and a health
missing-argument call left the directory unchanged. The existing bare-restart
refusal and project-open marker tests remained green.

The full `bb test` gate passed 542 tests with 4,403 assertions, all 20 dashboard
browser tests, and the bounded-projection E2E; the detached lifecycle E2E
completed in 1,494 ms. The `bb crap` clean gate passed 24 tests with 132
assertions and wrote `target/coverage/lcov.info`. The documentation contract
passed 3 documents and 5 sections.

No bare start, restart, stop or teardown was run against the active forge.

## Operator acceptance

None.
