# Bounded dashboard state projection E2E

Run from the `forgelet-swarmforge` checkout:

```sh
bash qa/serve-bounded-dashboard-state-projections.sh
```

The procedure uses temporary fixture roots only. It does not contact the active
forge, start a dashboard daemon, read credentials, or use operator data.

The script executes the card's projection scenarios through the shared
Clojure test runner. It checks:

- reuse of one snapshot and rendered automation body inside the 750 ms bound;
- coalescing of concurrent misses;
- synchronous write invalidation and direct-file expiry;
- fail-closed malformed refresh and successful recovery;
- root and Family isolation;
- the bounded Saibill-scale projection, repeated zero-build cache hits, and
  unchanged full `/api/state` compatibility.

A passing run prints the individual assertions and exits zero. A failing
scenario exits nonzero before any dashboard rollout is attempted.
