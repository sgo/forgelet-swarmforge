# make-dashboard-state-fast-and-probe-safe

# make-dashboard-state-fast-and-probe-safe

# Make dashboard state fast and reachability probe-safe

## Operator-observed problem

Saibill is healthy but intermittently appears offline. It currently has 13 open
projects, 420 tasks and 450 chat records. `/api/state` returns roughly 916 KB
and takes 3.07–3.16 seconds. The host uses the complete state response as its
reachability probe with an exact three-second timeout.

`/api/state` is a full authoritative dashboard snapshot, not a health check. Its
current implementation repeats expensive project collection:

- `project-slice` computes `tasks`, while `active-single-agent-card`,
  `project-idle?` and `work-in-flight` can each compute tasks again;
- board collection invokes shared board commands repeatedly;
- role activity invokes tmux session and pane reads;
- every request reparses all chat request files;
- all Family projects are assembled sequentially.

## Desired behaviour

- Add a minimal `GET /api/health` endpoint which performs no project, board,
  handoff, chat or tmux scan. It returns a small constant JSON liveness response
  and does not claim that state generation is current.
- Keep `/api/state` as the authoritative full snapshot with its existing public
  shape. Do not replace it with a summary in this card.
- Compute each open project's board/task view once per `/api/state` request and
  reuse that value for active-card, idle, task and work-in-flight fields.
- Avoid repeated board subprocesses for the same project and request. Reading
  the board file directly or reusing an mtime-keyed result is acceptable when
  it preserves lane, task, audit and status semantics.
- Collect pane/activity facts at most once per relevant live role and avoid
  invoking tmux for roles already known to be dead.
- Assemble independent project slices with bounded parallelism if measurement
  shows serial assembly remains material after removing repeated work.
- Preserve ordering in the returned response and do not expose partial state
  when any required project scan fails.
- Add a repeatable Saibill-scale fixture with at least 13 projects, 420 tasks
  and 450 chat records. Record response bytes and latency.
- Establish regression budgets: health p95 under 200 ms; state p95 around one
  second or better at that fixture on this machine.
- Keep existing clients working. Query-bearing chat pagination and mutation
  routes retain their current contracts.

## Host integration

The already-approved queued `forgelet-host` card
`cache-dashboard-state-in-host` will be amended before it starts:

- reachability comes from `/api/health`;
- full `/api/state` reads use a separate timeout appropriate to snapshot
  generation;
- Family health/state polls run independently and cannot block one another;
- the last good state remains available when only the snapshot is slow;
- `dashboard.changed` is emitted after replacing cached state.

The app card `preserve-open-family-through-reachability-flaps` remains the final
client defense: retain screen, cache, draft and focus through temporary
unreachability.

## Testing

- Health remains under 200 ms and returns the same tiny safe shape with zero,
  one and many projects.
- Health makes zero board, handoff, chat and tmux subprocess calls.
- A measured subprocess/read counter proves each project's task view is computed
  once, not repeatedly through active-card/idle/work paths.
- Current local-mode and forge-mode `/api/state` snapshots preserve their
  complete public fields and ordering.
- Boards with waiting, queued, active, merging and done cards retain statuses.
- Approvals, clarifications, pending/done chat, proposal actions, completion
  statistics and lieutenant/agent activity remain correct.
- Malformed or temporarily unreadable project data retains current bounded
  failure behavior and never emits a partial snapshot.
- Chat query pagination and mutations are unchanged.
- The 13/420/450 fixture records health and state latency and payload bytes;
  state p95 is approximately one second or better.
- A slow-state/healthy-health fixture proves the host integration can preserve
  reachability and cached state independently.
- Run all layer gates and the update exercise, then compose only from pushed
  `forgelet`.

## Documentation

Document that `/api/health` is liveness only, `/api/state` is the full
authoritative snapshot, the state performance budgets, internal reuse and
parallelism, and why state latency must not determine Family reachability.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change the app navigation model,
host cache policy or SSE event contract in this card. Do not remove or age out
chat history merely to meet the performance target.
