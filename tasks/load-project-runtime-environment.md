# load-project-runtime-environment

# Load the host project runtime environment

## Operator-observed need

The project deploy target must survive when Saibill, Innersource, Ledgerhall or
Forgelet starts a project without Forgelet Host as its direct parent. Relying on
`.zshrc` is insufficient because agent sessions are not all interactive
login shells.

## Desired behaviour

- The shared single-agent runtime launcher reads:

  ```text
  ${SWARMFORGE_PROJECT_RUNTIME_ENV:-$HOME/.config/forgelet/project-runtime.env}
  ```

- If present, it sources the file before starting the agent process in the
  persistent tmux session.
- Variables already supplied explicitly to the launcher remain authoritative;
  the file supplies defaults, not overrides.
- A missing file is allowed for hosts without extra project environment.
- An unreadable or malformed file fails the runtime launch visibly rather than
  silently omitting deploy configuration.
- This applies to a normal project open and to direct agent-runner restart, not
  only the runtime opened immediately after Forgelet Host creates a project.
- The project checkout contains only the generic file-path/variable names; no
  device-specific value is written into Git.

## Acceptance

- Fixture tests launch a single-agent runtime with no inherited snapshot
  variable but a private runtime environment file, and prove the agent child
  receives the value.
- Fixture tests prove a caller-supplied value overrides the file.
- Tests prove missing files are harmless, while unreadable/malformed files
  fail visibly.
- A restart through the forge path, not the host creation path, retains the
  value.
- Existing agent selection, PATH setup, NO_COLOR handling and session naming
  remain intact.
- Layer `code` and `clean` stages pass on the committed `forgelet` tree.
