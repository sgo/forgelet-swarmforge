# deliver-large-completed-card-reports

# deliver-large-completed-card-reports

# Deliver completed-card reports beyond the argv limit

## Operator-observed problem

The Saibill completed-card report for 178 cards cannot be delivered. The host
creates report ownership `req-report-e7758be367cfd93c5bb338d95c6b791b` for
thread `thread-fb9f3f9a9affc19f5c192e2d17352894`, then returns
`502 completed-card report could not be delivered`.

Saibill's dashboard is running the atomic layer. A controlled tmux session on
Saibill's socket proves the wake fails while setting the buffer: the complete
29 KB report is passed to `tmux set-buffer` as one command-line argument, and
the OS returns `command too long`. Short wakes succeed.

## Desired behaviour

- Load the named tmux wake buffer through `tmux load-buffer -`, with the report
  body supplied on stdin.
- Never place the report body, credentials or dashboard paths in process
  arguments.
- Preserve the existing one-paste-and-one-Enter sequence.
- Preserve request locking, deterministic idempotence, metadata validation and
  pending-request removal after a genuine wake failure.
- A report containing every currently completed card must deliver without a
  size-dependent command failure.

## Testing

- Add a regression using a completed-card report larger than the OS
  command-line limit, with multiline text and 178 or more cards.
- Prove the request writes, the exact body reaches a real tmux pane, the same
  request replays without another wake and conflicting reuse is refused.
- Prove genuine tmux failure still removes the newly written pending request
  and returns the bounded wake error.
- Run the complete layer suite.

## Boundary

- Work in `projects/forgelet-swarmforge` on the `forgelet` branch.
- Do not weaken report validation, idempotence or wake-failure cleanup.
- Do not change host report ownership or create a second message store.
- Publish and compose before retrying Saibill's existing report identity.
