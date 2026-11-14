# install-and-verify-agent-launch-credentials

# Install and verify agent launch credentials

## Operator-observed need

The completed layer card added the credential-hook boundary to
`load-project-runtime-env.sh`, but the rollout is not complete:

- this devbox currently has no
  `$HOME/.config/forgelet/agent-credentials.sh` owner-only hook;
- the composed `forgelet-app` and `forgelet-host` project copies still have the
  previous loader;
- running project agents predate the change and therefore cannot receive its
  environment.

Without this rollout the completed loader is inert, and completed cards can
still leave pushes outstanding.

## Desired deployment tooling

- Add a non-interactive installer/check command that creates or verifies the
  owner-only device hook using existing Keychain item names. It must never print
  token values, write them into Git, or place them in command arguments.
- The hook must load `GH_TOKEN` for every project and
  `GITHUB_PACKAGES_TOKEN` only when the loader detects a Maven/registry project.
- Fail closed on a symlink, wrong ownership, non-owner-only mode, malformed hook,
  missing Keychain item, failed Git authentication or unavailable package
  credential where required.
- Compose the published loader/hook support into this forge's project copies.
- Restart affected project agents after composition so their persistent sessions
  inherit the environment.
- Verify by variable presence only in each launched child, then prove Git access
  and, for a Maven project, package resolution. Never inspect or print process
  environment values.

## Testing

- Installer/check succeeds idempotently with both Keychain items present and
  fails with bounded diagnostics for missing items, unsafe hook permissions or
  ownership, malformed syntax and missing `gh` authentication.
- A normal project receives `GH_TOKEN` but not the package token; a Maven
  project receives both.
- Existing explicitly supplied values still win.
- Compose from pushed `forgelet`, restart a disposable project agent and verify
  child environment variable names plus non-sensitive access probes.
- Run the full layer/provisioning gates and update exercise.

## Boundary

Work in `projects/forgelet-swarmforge` plus this forge's device-local rollout.
Do not commit the hook, secrets, local project paths or token values. This card
does not add repository-creation authority.

## Verification

The installer/check fixture covers idempotent installation, missing Keychain
items, symlinks, unsafe modes, wrong ownership, malformed shell, failed `gh`
authentication, ordinary and Maven token scoping, and absence of token values
from output. `bb test` passed 447 tests with 3,090 assertions plus all 17
dashboard browser tests. `bb crap` passed the complete coverage/CRAP gate.

The device hook was installed through the published command, is a regular
mode-600 file owned by the current user, passes shell syntax and `--check`, and
contains lookup commands rather than credentials. The shared layer was composed
into the Forgelet forge; all five open project copies carry the same loader and
installer hashes as the source.

Four independent project agents were restarted after composition. The
source-layer agent was left running because it owns this card, and the same
updated loader was verified through a fresh child probe. Each project launch
probe reported variable presence only and completed a Git read. Ordinary
projects had `GH_TOKEN` present and `GITHUB_PACKAGES_TOKEN` absent. The Maven
project had both present, and dependency resolution through GitHub Packages
succeeded. No process environment values or token values were inspected or
printed.

## Operator acceptance

None.
