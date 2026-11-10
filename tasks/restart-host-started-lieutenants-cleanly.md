# restart-host-started-lieutenants-cleanly

# restart-host-started-lieutenants-cleanly

# Restart host-started Lieutenants with a usable terminal environment

## Operator-observed problem

Saibill's Claude Code Lieutenant repeatedly rendered command-completion
suggestions as real unsent input, and deleting the text did not stop it
returning. The pane also had no syntax or UI colours. The attached tmux client
was correctly `xterm-kitty`, but the stale server environment was:

```text
TERM=dumb
COLORTERM=
NO_COLOR=1
```

Forgelet and Innersource, whose server environments use `TERM=xterm-kitty` and
have `NO_COLOR` unset, do not show the defect.

The existing no-colour fix clears `NO_COLOR` only for project agents through
`load-project-runtime-env.sh`. Forge Lieutenants launch directly and are still
exposed to the host process environment. The normal role repair path also fails
on Saibill because its existing `swarmforge.conf` has the supported legacy
`Lieutenant claude` form, while the current parser expects at least four
fields.

## Desired behaviour

- Remove inherited `NO_COLOR` at every persistent role launch boundary, including
  forge Lieutenants.
- When a role starts with `TERM=dumb` inside a colour-capable tmux session,
  normalise the pane environment to a usable tmux terminal while preserving
  valid caller-provided `TERM`, `COLORTERM`, `LANG` and project runtime values.
- Make `--restart-role` and `--restart-dead-roles` accept or safely migrate the
  legacy `Lieutenant <agent>` configuration form used by existing forges.
- Ensure a restarted Lieutenant keeps the configured prompt, permissions,
  authentication and forge identity.
- Keep project sessions and their tmux servers untouched.

## Testing

- Launch a forge from a parent with `TERM=dumb` and `NO_COLOR=1`; prove the
  Lieutenant child receives a usable terminal and no `NO_COLOR`.
- Prove valid Kitty and tmux terminal values survive unchanged.
- Restart a legacy `Lieutenant claude` configuration through the public role
  restart command and confirm the configuration remains valid.
- Cover missing prompts, unsupported agents and restart failure without
  deleting unrelated role or project sessions.
- Run the complete layer suite.

## Deployment

Publish and compose the layer, then restart Saibill and Ledgerhall Lieutenants
through the fixed lifecycle. Verify colours, authenticated Claude startup and
the absence of the terminal-dumb completion artifact. Project runtimes remain
running.

## Boundary

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not edit
active forge roots, themes or project copies directly.
