# expose-project-completion-statistics

# Expose project active-card completion statistics

The app project buttons will replace `Open project` / `Closed project` with a
compact performance summary. The family dashboard currently discards the board
row's creation time and exposes no aggregate, so the app cannot calculate a
consistent metric.

Add one deterministic `completion_stats` object to every project entry in the
forge `/api/state` `projects` array. Freeze this shape:

```json
{
  "completed_cards": 12,
  "median_seconds": 8280,
  "mean_seconds": 11100,
  "min_seconds": 1320,
  "max_seconds": 100800
}
```

When no valid completed-card durations exist, `completed_cards` is `0` and the
four statistic fields are `null`. Do not emit sentinel zero durations.

Definition:

- A completed card is a board card whose final lane is `done`.
- Its active completion duration starts when it enters `spec` and ends when it
  enters `done`.
- Once the queued-card lifecycle records `activated_at` for the `queued` to
  `spec` transition, that timestamp is authoritative. Time spent queued is
  excluded.
- Existing cards predate `activated_at`; because current routing puts them in
  `spec` at creation, their board `created_at` is the legacy activation fallback.
  Never use original creation time for a card that has an activation record.
- Exclude malformed, missing or negative durations rather than coercing them to
  zero. Stable rounding is done by the consumer for display; the API reports
  integral seconds.

Compute these statistics once in the shared dashboard project slice so desktop
and phone consumers cannot drift. Do not return raw credential, path or session
state. Existing state consumers that do not know the field must remain
compatible.

Add fixture coverage for zero, one and several completed cards; quick and
long-tail distributions; queued cards excluding queue time; legacy fallback;
reopened cards; malformed timestamps; non-done cards; the project placeholder
row being ignored; and deterministic count/median/mean/min/max results. Update
the dashboard API documentation. Run all layer gates and the upgrade exercise;
compose only after the queued-card lifecycle's activation record has landed or
adapt to its final frozen field name without changing the statistics contract.
