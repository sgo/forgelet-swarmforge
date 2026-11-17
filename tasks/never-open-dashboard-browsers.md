# never-open-dashboard-browsers

# Never open dashboard browsers

## Operator-observed problem

Safari windows frequently appear on dashboard pages during development. Shared
dashboard startup invokes `open <dashboard-url>` by default, including when the
layer tests start dashboard processes. `SWARMFORGE_OPEN_BROWSER=0` suppresses
the behavior, but the operator wants automatic opening removed entirely.

## Desired behaviour

- Dashboard startup never launches a browser.
- Remove the `SWARMFORGE_OPEN_BROWSER` toggle and the helper that invokes the
  platform `open` command.
- Continue starting and supervising the dashboard normally, writing its URL and
  port and printing the URL for the operator.
- Preserve dashboard readiness, startup failure handling, sticky port behavior
  and all bridge/host consumers.
- Update tests and documentation that currently suppress browser opening with
  the environment variable.
- Do not add another command, hook or opt-in path that starts a browser.

## Testing

- Unit and script tests prove dashboard startup remains successful when no
  browser launcher is available.
- A test-only fake `open` executable proves no invocation occurs, regardless of
  environment.
- Existing dashboard URL, port, supervisor, stop/start, upgrade and failure-path
  tests remain green.
- Search proves no production or test path invokes `open` for dashboard URLs.

## E2E

Start, stop and restart a temporary forge dashboard with a fake `open` command
first on `PATH`. Confirm the dashboard starts, writes and prints its URL, and
the fake command is never called.

## Boundary

Work in `projects/forgelet-swarmforge`. Keep the dashboard server and its URL
artifacts; remove only automatic browser launching and its configuration.

## Verification

- `bb test`: 488 tests, 3,610 assertions, 0 failures, 0 errors; all 21 browser
  tests and the required bounded-projection E2E passed.
- `bb crap`: 24 coverage tests and 132 assertions passed; the complete coverage
  and CRAP gate passed.
- A restricted-PATH script test started and stopped a supervised dashboard
  without a browser launcher while preserving the printed URL, URL file, port
  file, health and supervisor status.
- A fake `open` executable first on PATH was never called across dashboard
  start, stop and restart.
- Production and test source no longer reference `SWARMFORGE_OPEN_BROWSER`,
  `open-browser?` or `maybe-open-browser!`. No production path invokes `open`
  for a dashboard URL.

## Operator acceptance

None.
