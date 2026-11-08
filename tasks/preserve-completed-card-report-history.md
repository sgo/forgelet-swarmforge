# preserve-completed-card-report-history

# Preserve completed-card report history

## Operator-observed need

Completed rows are local, uncommitted board state. The report flow will verify
them and remove them from the local boards. Without a durable report history,
that cleanup also erases the evidence used by project completion statistics.

## Desired behaviour

- Define a durable, forge-owned completed-card report record.
- Store one record per report with:
  - report id and completion time;
  - Family and reporting Lieutenant;
  - each reported project, task id, card name and completion timestamp;
  - verification result, with retained cards stated explicitly.
- Keep the human-readable report in the forge `backlog.md`.
- Add a machine-readable history that survives board cleanup and can be
  committed by the Lieutenant.
- Make completion statistics read cumulative verified reports plus any current
  board `done` rows not yet reported.
- Keep the progress-bar done count as the current unreported done count, so
  reporting a batch visibly clears that batch while historical statistics
  remain available.
- Reject duplicate report ids and preserve original evidence when a report is
  retried.

## Testing

- Report verified cards, remove their board rows, and prove cumulative
  completion stats remain unchanged.
- Prove the progress-bar current done count drops after cleanup.
- Cover partial verification, retained cards, repeated reporting, malformed
  history records and reconstructing stats from current plus historical data.
- Ensure no absolute host paths or tokens enter committed records.

## Out of scope

The report button/chat UI or verification mechanics; those belong to the
completed-card report cards.
