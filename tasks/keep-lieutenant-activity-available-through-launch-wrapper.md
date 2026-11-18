# keep-lieutenant-activity-available-through-launch-wrapper

# Keep Lieutenant activity available through the launch wrapper

## Operator-observed problem

Desktop and phone show `Activity unknown` for every Lieutenant even though all
four Lieutenants are alive and project-agent activity displays normally.

The credential-loader launch places a `zsh` launch-script parent around the live
Codex or Claude child. The tmux pane reports that wrapper through
`pane_current_command`. `pack_web.bb` treats the shell name as proof of an empty
fallback shell and emits `alive: true, state: unavailable`.

## Desired behavior

- Distinguish a launch wrapper with a live expected agent descendant from a
  genuinely empty fallback shell.
- Read and classify the Lieutenant pane while the live child exists.
- Preserve Ready, Working and Unknown activity for both Codex and Claude
  Lieutenants.
- Use one process-tree decision rule shared with the role restart lifecycle
  rather than a second shell-name heuristic.
- Keep project-agent activity unchanged.

## Testing

- A wrapper shell with a live Codex child reports the expected pane text and is
  not considered a fallback shell.
- A wrapper shell with a live Claude child behaves equivalently.
- A bare shell with no agent descendant remains fallback/unavailable.
- Malformed or exited children fail bounded and do not expose pane contents
  beyond the existing activity contract.
- Ready, working, unknown and unavailable fixtures retain their exact state.
- Existing project activity, heat, status-line redaction and bounded projection
  tests remain green.

## E2E

Launch a disposable host Lieutenant through the credential-loader wrapper and a
fake interactive agent, then read `/api/automation-state` and prove the
Lieutenant is available and classifiable while the child remains alive. Replace
the child with a bare shell and prove it becomes unavailable.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change host state normalization,
infer activity from project work, restart the active forge as a test, or weaken
pane redaction and bounding.

## Verification

Live wrapper-based Lieutenants remain available to the dashboard activity
classifier, while genuinely empty shell panes remain unavailable.

## Operator acceptance

None.
