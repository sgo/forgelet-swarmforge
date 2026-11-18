# load-github-credentials-at-lieutenant-launch

# Load GitHub credentials at Lieutenant launch

## Operator-observed problem

Project agents now launch through `load-project-runtime-env.sh` and their live
child processes have the required credentials, but forge Lieutenants launch
directly through `swarmforge.bb`'s shared launch command. The current Lieutenant
environment has neither `GH_TOKEN` nor `GITHUB_PACKAGES_TOKEN`, so Git operations
that should be automatic require an operator or Lieutenant to invoke the loader
manually.

Existing processes cannot receive a changed environment. The fix therefore
needs both a launch-boundary change and a controlled Lieutenant restart.

## Desired behaviour

- Route the final Lieutenant agent command through
  `load-project-runtime-env.sh` at the same launch boundary used by initial forge
  startup and `--restart-role`.
- Keep credential loading inside the persistent child process. Do not copy
  tokens into the parent `swarmforge.sh` process, tmux server environment,
  tmux environment files, command arguments, logs or committed files.
- Preserve the configured agent command, prompts, permissions, terminal
  normalization, role identity and existing exit-status/cleanup suffix.
- Keep `GH_TOKEN` available to the Lieutenant.
- Keep `GITHUB_PACKAGES_TOKEN` conditional on the loaded root's declared
  Maven/package-registry need rather than exposing it universally.
- Apply the same boundary to any other persistent role still launched directly
  through the shared command, while leaving the already credential-aware
  project agent runner unchanged.
- Fail visibly at startup for a GitHub-enabled forge when the owner-only hook is
  present but invalid, or when required GitHub authentication cannot be
  established.
- Preserve offline/local forge startup when GitHub credentials are genuinely not
  configured, unless that forge explicitly requires GitHub.

## Implementation decisions

- Treat `swarmforge.bb`'s shared persistent-role launch path as the single
  credential boundary; do not ask individual Lieutenant prompts to source
  credentials.
- Avoid quoting the full agent command as one literal argument to the loader.
  Generate a runtime launch-command file or use an equivalent safe mechanism so
  prompt expansion, flags and agent-specific quoting remain unchanged.
- Run the loader before the agent process but outside tmux state, then preserve
  the existing shell status and cleanup invocation after the agent exits.
- Verify the launch command through the existing test-only Lieutenant command
  surface and a fixture hook that exports synthetic, non-secret sentinels.
- Do not inspect or print real process environment values. Live verification may
  test only variable presence and run non-sensitive `gh auth status` or
  `git ls-remote`.

## Testing

- Unit/acceptance coverage proves the Lieutenant launch command enters the
  credential loader before the configured agent command.
- A fake owner-only hook proves `GH_TOKEN` presence reaches the child, while no
  ordinary forge root receives an unnecessary package token.
- Prompt expansion, Codex/Claude arguments, permission flags, terminal values,
  exit status and the existing cleanup suffix remain unchanged.
- A malformed, symlinked or incorrectly permissioned hook fails visibly and
  does not start a partially authenticated Lieutenant.
- A forge without a credential hook still starts in the documented offline
  mode unless GitHub is explicitly required.
- No token value appears in stdout, stderr, tmux state, runtime files or command
  arguments.
- `--restart-role` and ordinary start use the same credential-aware launch path.

## Deployment and verification

- Compose the reviewed `forgelet` layer into all idle forge roots.
- Restart only each forge Lieutenant through the normal role lifecycle. Do not
  restart project agents, which are already verified credential-aware.
- Inspect each launched Lieutenant child by variable presence only.
- Run `gh auth status` in each Lieutenant environment and a non-mutating Git
  read for each forge's configured origin.
- Confirm the Forgelet, Saibill, Innersource and Ledgerhall Lieutenants are alive
  and their dashboards remain healthy.

## Boundary

Work in `projects/forgelet-swarmforge` and the device-local forge rollout. Do not
put credentials in tracked files, alter project-agent credential scoping, widen
the package token to non-package roots, or change host repository administration.

## Verification

- `bb test`: 489 tests, 3,640 assertions, 0 failures, 0 errors; all 20 browser
  tests and the required bounded-projection E2E passed.
- `bb crap`: 24 coverage tests and 132 assertions passed; the complete coverage
  and CRAP gate passed.
- Synthetic owner-only hooks proved `GH_TOKEN` reaches the Lieutenant child,
  ordinary forge roots receive no `GITHUB_PACKAGES_TOKEN`, malformed hooks fail
  before launch, and no synthetic token appears in command text, runtime launch
  scripts or output.
- The reviewed layer was composed into Forgelet, Saibill, Innersource and
  Ledgerhall with bridge reinstallation disabled. Only their Lieutenant
  sessions were replaced; project runtimes were untouched.
- All four Lieutenant agent children expose `GH_TOKEN` and do not expose
  `GITHUB_PACKAGES_TOKEN`. `gh auth status --hostname github.com` and a
  non-mutating `git ls-remote origin` succeed through each forge's credential
  loader.
- All four dashboards return `{"ok":true,"live":true}`. Runtime launch scripts
  are owner-only mode `700`, and neither credential variable appears in tmux
  server state.
- A hermetic test guard now refuses restart, start, stop, direct-listener,
  agent-runner and composition commands unless the actual fixture root or tmux
  socket is disposable. It is committed at `8a23e11`.
- No project agent was restarted. Operator acceptance remains pending.

## Operator acceptance

- owner: operator | environment: restarted forge Lieutenants | checks: issue one GitHub-backed request or push from a Lieutenant without manually sourcing credentials, while no credential value appears in output | status: pending
