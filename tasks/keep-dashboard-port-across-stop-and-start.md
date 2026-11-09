# keep-dashboard-port-across-stop-and-start

# keep-dashboard-port-across-stop-and-start

# Keep the dashboard port across stop and start

## Operator-observed problem

The dashboard is designed to reuse its previous port after restart, but a
standalone stop followed by a standalone start assigns a new random port.
During recovery on 2026-11-09, Forgelet moved from 50684 to 54710, Saibill from
50696 to 58048 and Ledgerhall from 63811 to 49419. Their Tailscale Serve routes
then pointed at stopped listeners.

`dashboard-url` currently serves two incompatible purposes:

- it is the live runtime address consumed by the bridge, host and health
  checks; and
- it is the only remembered port preference used by the next start.

Deleting it on stop correctly removes the live claim, but also forgets the
preferred port. Retaining it would advertise a stopped dashboard as live.

## Desired behaviour

- Keep `dashboard-url` as the runtime-liveness record and continue deleting it
  when the dashboard stops.
- Add a separate stable `dashboard-port` preference.
- On a successful bind, record the serving port in `dashboard-port`.
- On start, read `dashboard-port` before stopping an existing dashboard and
  attempt that port first, whether called as part of restart or after a
  standalone stop.
- If the preferred port is occupied or unusable, choose an available port and
  update `dashboard-port`.
- Migrate existing forge state by seeding `dashboard-port` from the current
  `dashboard-url` when the new file is absent.
- Keep the existing distinction between a free port and one in `TIME_WAIT`.
- Keep Tailscale Serve mapping outside this card; this card makes the local
  target stable so that those mappings survive normal stop/start cycles.

## Testing

- Stop and start as separate commands and prove the same port returns.
- Start after an abrupt process exit and prove the preferred port is reused
  when available.
- Occupy the preferred port and prove startup selects a new port and updates the
  preference.
- Prove stop still removes `dashboard-url` and `pack_web.pid` while retaining
  `dashboard-port`.
- Migrate a forge with only `dashboard-url`, a forge with both files and a forge
  with malformed or unsafe port state.
- Cover restart through the combined start path, direct stop/start, supervisor
  failure and a port in `TIME_WAIT`.
- Run the complete layer suite.

## Boundary

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not edit
active forge roots. Do not change dashboard liveness semantics or Tailscale
Serve policy in this card.
