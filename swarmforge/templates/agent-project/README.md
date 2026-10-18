# Single-agent project

This project is owned by one agent. It has a `stages.edn` and a `STAGES.md`
instead of pack roles and handoffs. `./swarm` starts the local runner; the
runner serves `pack_web` for this project and gives the agent one dashboard
request at a time.

The forge dashboard marks this project as single-agent and links to the
project's own dashboard.
