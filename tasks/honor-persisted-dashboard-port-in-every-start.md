# honor-persisted-dashboard-port-in-every-start

# Honor persisted dashboard port in every start

## Operator-observed problem

The four Family dashboards restarted on ports `62848`, `62856`, `62876` and
`62896`, while their `.swarmforge/dashboard-port` files still recorded `50684`,
`50696`, `50700` and `63811`. The live tmux commands invoke canonical
`pack_web.bb --serve <root>` directly without a port argument.

`swarmforge.bb` owns the sticky-port selection and bookkeeping, but `pack_web.bb`
owns the actual listener and ignores `dashboard-port`. Any supported entry path
that starts the listener directly therefore chooses an ephemeral port and leaves
the persisted port misleading.

## Desired behaviour

- When no explicit port argument is supplied, `pack_web.bb` reads
  `.swarmforge/dashboard-port` and tries that port first.
- If the persisted port is valid but unavailable, fall back to an ephemeral
  port rather than failing to start.
- After a successful bind, write the actual port atomically to
  `.swarmforge/dashboard-port`, then publish the dashboard URL and PID as today.
- An explicit numeric port argument remains authoritative.
- Project dashboards without a persisted port remain ephemeral.
- Preserve supervisor, stop/start, URL file, readiness, health and all
  bridge/host consumers.
- Keep `swarmforge.bb` compatible with the new ownership; remove duplicate
  bookkeeping only where doing so cannot change explicit-port behavior.

## Testing

- Direct `pack_web.sh --serve` uses a valid persisted port and leaves the file
  unchanged.
- Direct start with no persisted port chooses an ephemeral port and records it.
- An occupied persisted port falls back to an ephemeral port and updates the
  file to the actual bound port.
- An explicit port overrides the persisted port and becomes the newly recorded
  port.
- `swarmforge.bb` start/stop/restart, sticky-port, failed-start and project
  dashboard paths remain green.
- Dashboard health and URL publication remain unchanged.

## E2E

Start a temporary forge dashboard directly with `pack_web.sh --serve`, stop it,
and start it again without a port. Confirm both runs use the same recorded port.
Occupy that port before a third start and confirm the dashboard starts on a
fallback port that matches the updated `dashboard-port` file.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change Tailscale Serve,
devbox-specific mappings or project dashboards that do not carry a persisted
port.

## Verification

- `bb test`: 486 tests, 3,598 assertions, 0 failures, 0 errors; all 21 browser
  tests and the required bounded-projection E2E passed.
- `bb crap`: 24 coverage tests and 132 assertions passed; the complete coverage
  and CRAP gate passed.
- Direct-start E2E covered first ephemeral bind and recording, restart on the
  same recorded port, and a third start falling back when that port was held.
- Focused direct-start tests covered an available persisted port, an occupied
  persisted port, an explicit override, health reachability, and unchanged
  URL/PID publication.
- Existing supervisor start/stop/restart, sticky-port, failed-start and
  project-dashboard paths remained green.

## Operator acceptance

- owner: operator | environment: all four Family dashboards | checks: restart the dashboards through each supported path and confirm live ports match `dashboard-port`; after the next composition, confirm Tailscale Serve can be mapped to the recorded ports without fallback | status: pending
