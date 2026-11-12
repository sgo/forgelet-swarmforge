# propagate-github-credentials-to-agent-launches

# Load GitHub credentials at persistent-agent launch

## Operator-observed need

The GitHub and GitHub Packages tokens are already stored in the devbox
Keychain, exported by login shells, and checked during provisioning. Persistent
project agents do not start from a login-shell environment, so the current
single-agent agent still lacks both `GH_TOKEN` and `GITHUB_PACKAGES_TOKEN`.
Completed cards can therefore leave their push outstanding, and Maven agents can
fail late when resolving package artifacts.

## Remaining scope

- Add one optional credential-loading hook to
  `load-project-runtime-env.sh`, immediately before its final
  `exec bash -c "$1"`.
- Source one owner-only device-local hook from `$HOME/.config/forgelet/`.
  The hook reads both existing Keychain items and exports them. Do not write
  token values into the tracked loader, project configuration, repository files
  or logs.
- Never execute the credential hook as a separate child process whose exported
  environment would be lost. Source it into the shell that executes the agent.
- Preserve existing environment values: an explicitly supplied variable wins
  over the hook, matching the loader's current no-override behavior.
- `GH_TOKEN` is needed by every project agent that pushes. Expose
  `GITHUB_PACKAGES_TOKEN` only when the project declares a Maven/package-registry
  need; do not grant the classic packages token to every agent by default.
- Keep the two token types distinct. Do not substitute `GITHUB_PACKAGES_TOKEN`
  for `GH_TOKEN`, or vice versa.
- Configure and verify Git authentication through `gh` without logging token
  values. Presence/access checks may report variable names only.
- Update usage/documentation and fail with bounded diagnostics if a required
  hook is present but malformed or cannot load a required credential.

## Deployment and rollback

- The layer change installs the generic hook boundary.
- This forge's operator-owned hook is provisioned outside the project tree and
  is not committed. Restart persistent agents after installation because
  existing processes cannot acquire the new environment.
- If the hook is absent, agents must continue to launch as they do now; the
  loader change is backward compatible.

## Testing

- Cover absent hook, valid hook, malformed/non-readable hook and existing-value
  precedence.
- Prove the executed child receives `GH_TOKEN` for an ordinary project and both
  variables only for a project declaring a packages need.
- Prove token values never appear in stdout, stderr or diagnostics.
- Prove `git ls-remote`/push authorization and Maven package resolution in the
  appropriate launch contexts.
- Restart the affected Forgelet agents and verify variable presence by name only.
- Run the full layer gates and update exercise.

## Boundaries

Work in `projects/forgelet-swarmforge`. Do not put secrets in Git, change token
types, add repository-creation authority to agents, or overwrite explicitly
configured environment values. Repository creation remains the host's separate
admin-credential responsibility.

## Verification

Loader probes cover absent and valid device hooks, caller precedence, token
scoping, bounded failures, Git authentication setup and package-resolution
context, plus the complete layer and update-exercise gates.

## Operator acceptance

None.
