# expose-lieutenant-runtime-activity

# expose-lieutenant-runtime-activity

# Expose Lieutenant runtime activity

## Operator-observed need

`Waiting for Lieutenant` currently describes only a pending request. It cannot
distinguish a Lieutenant that is working, alive and idle, unknown, or no longer
running. The operator wants an evidence-based Working/Ready/Unavailable
indicator so a dead Lieutenant does not look like an answer still on its way.

## Desired dashboard fact

Add a bounded `lieutenant_activity` object to forge `/api/state`:

- `alive`: whether the configured master session exists;
- `state`: `working`, `idle`, `unknown` or `unavailable`;
- `heat`: bounded measured recent pane activity, not decorative animation;
- `last_changed_at`: when the structured state last changed;
- `status_line`: at most one short recognized safe pane-status line, optional.

Classify:

- missing session or dead/fallback shell as `unavailable`;
- recognized active output as `working`;
- recognized completed/idle output as `idle`;
- alive but not confidently classifiable as `unknown`.

Never expose tmux socket names, pane commands, filesystem paths, credentials or
unrelated full transcript content.

## Testing

- Alive working, alive idle, alive unknown and missing/dead session.
- Fallback shell must be `unavailable`, not `idle`.
- Pane output changes increase measured heat; unchanged heartbeats do not.
- Stale recognized status cannot override a missing session.
- Activity changes participate in dashboard state fingerprints so the host/app
  can update.
- Safe output bounds, malformed pane text and no-transcript behavior.
- Preserve existing dashboard state and work-queue activity fields.
- Run the complete layer suite.

## Boundary

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not change
host projection or app presentation in this card.
