<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge, installed for Forgelet

SwarmForge coordinates AI agents in isolated git worktrees and tmux sessions.
Agents exchange committed work through durable handoffs, while the operator uses
a local dashboard to start work, inspect agents, handle approval gates, answer
clarifications, and stop the swarm.

![SwarmForge dashboard](project-swarm.jpg)

This repository is
[`forgelet-swarmforge`](https://github.com/sgo/forgelet-swarmforge), the Forgelet
family's fork of SwarmForge. `get-swarm-forge` installs from it, so a forge
composed here runs SwarmForge *plus* the layer this family operates its forges
with — including the bridge that puts a forge on the operator's phone.

## The Forgelet layer

| Branch | What it is |
|---|---|
| `main` | Uncle Bob's SwarmForge, unmodified. It is only ever fast-forwarded from upstream. |
| `forgelet` | The shared runtime this family runs: upstream's `main` plus the Forgelet layer. |

The layer is additive and small. It carries the operator's card tools
(`approve_task`, `nudge_role`), the completion-hook runner (`run_hook`), a
dashboard that keeps its address across restarts, a `kitty` terminal adapter, the
language and tool registry, and the homeserver helper
(`matrix-homeserver.sh`) that gives a bridge's rooms a Synapse to run on.

The packs come from this same repository, so the helper installs everything a
Forgelet forge needs from one branch. Anything upstream should have for its own
sake is offered there first as a pull request; `forgelet` carries it until it
lands, and both branches stay rebaseable onto upstream with no Forgelet history
in the way. `main` is the landing page, installer source, shared runtime, and
shared engineering law — it is not itself a runnable SwarmForge product.

### Install the helper

```sh
mkdir -p ~/cmds
curl -L -o ~/cmds/get-swarm-forge \
  https://raw.githubusercontent.com/sgo/forgelet-swarmforge/forgelet/get-swarm-forge
chmod +x ~/cmds/get-swarm-forge
```

Put `~/cmds` on `PATH`. The helper is the supported entry point because it
composes files from more than one branch, and it keeps itself current from the
layer it downloads, so a stale copy on a machine repairs itself.

### Start a forge

```sh
mkdir -p ~/my-forge && cd ~/my-forge
get-swarm-forge project-manager      # a forge with selectable packs
./swarm                              # dashboard, host lieutenant, projects/ waiting
```

Set `SWARMFORGE_REPO_URL` to `https://github.com/unclebob/swarm-forge` and
`SWARMFORGE_BASE_REF` to `main` to compose Uncle Bob's products instead, or point
`SWARMFORGE_GIT_DIR` at a local checkout to compose from a branch you have not
pushed.

The same command composes the bridge, because a forge that cannot reach a phone
is half a forge. It downloads this family's bridge,
[`forgelet-bridge`](https://github.com/sgo/forgelet-bridge), builds it on the
machine that will run it — no cross-compilation and no published binaries —
copies the adapter into the forge's own scripts, and installs the tools and the
rules a bridge's rooms rely on, from that fresh copy rather than from anything
already in the forge. It also lays the built bridge out under
`projects/forgelet-bridge/`, where the adapter's own defaults look for it. A
forge that already keeps the bridge there as a project — that directory is a
checkout, with its own history and its own build — keeps that one instead, and it
is the build the adapter then runs. Starting a bridge is `matrix-bridge.sh
start`, once a configuration exists. See *Hook the forge into a bridge* below.

Three overrides are for development rather than for choice:
`SWARMFORGE_BRIDGE_DIR` builds from a local checkout instead of downloading,
`SWARMFORGE_BRIDGE_REPO` and `SWARMFORGE_BRIDGE_REF` name another repository or
branch, and `SWARMFORGE_SKIP_BRIDGE=1` leaves the bridge out entirely.

### Where your own things go

Composition writes the paths it names and nothing else, so everything in this
table survives every update with nobody doing anything. Each is either
`local-`-prefixed or sits beside the shared file it belongs to, so the next
session — and the next forge of the family — can see it is ours.

| Yours | Where it goes |
|---|---|
| A helper script for this forge | `swarmforge/local-scripts/`, which is on every session's `PATH` |
| Rules for this forge's lieutenant | `swarmforge/roles/local-lieutenant.prompt`, read after the shared lieutenant prompt |
| A project's language | the project's `swarmforge/language.conf`, which wins over its pack's default |
| Rules for one project | that project's `local-*.prompt` articles, beside the shared articles |
| What runs when one of its cards lands | that project's `swarmforge/hooks/card-complete.sh` |
| How this forge is configured | the forge's `swarmforge/swarmforge.conf` |

What is *not* yours is the shared runtime: `swarmforge/scripts/` is replaced
wholesale on every update, which is why a helper looks like a sibling of it
rather than a file inside it.

### Update a forge

Run the same command in the forge again. It updates in place, and it is worth
knowing exactly what that means:

- **Replaced**: the shared runtime (`swarmforge/scripts`), the three shared
  articles, `constitution.prompt`, `lieutenant.prompt`, the `swarm` launcher, and
  every `packs/<name>` — so a forge's behavior follows this fork's `forgelet`
  branch from then on.
- **Kept**: everything the table above names, and everything under
  `.swarmforge/` — the dashboard address, the board, the handoffs, the sessions —
  because that is state rather than composition.
- **Untouched**: `projects/`, with the one exception named under *Start a forge* —
  a forge that does not keep the bridge as a project of its own gets a freshly
  built one laid out at `projects/forgelet-bridge/`, because that is where the
  adapter's defaults look for it. A project carries its own copy of the runtime,
  and that copy is refreshed when the dashboard opens or refreshes the project,
  not by this helper. A directory that is a checkout is left alone.

Run it while the forge is stopped, so nothing is mid-launch.

One thing an update cannot restore is work a bridge installed. The kit — the
route gate, the idler check, the stall watch, and the doorbell — and the rules a
bridge's rooms rely on are *installed into* a forge rather than composed by this
helper, so a bridge-served forge reinstalls them after an update:

```sh
root="<forge root>"
adapter="<bridge repository>/scripts/matrix-bridge.sh"
MATRIX_BRIDGE_FORGE_ROOT="$root" "$adapter" install-kit
MATRIX_BRIDGE_FORGE_ROOT="$root" "$adapter" install-rules
```

### Hook the forge into a bridge

A bridge is what gives a forge its phone: approvals, clarifications, and chat
travel through Matrix, and the bridge does the talking. Two cases when a forge is
new:

- **No bridge yet.** Install one — [`forgelet-bridge`'s
  README](https://github.com/sgo/forgelet-bridge#readme) covers the bridge and its
  homeserver, which `swarmforge/scripts/matrix-homeserver.sh` can initialise,
  start, stop, and create users on — then add this forge to it.
- **A bridge and homeserver are already running.** Add one entry for this forge
  root to the bridge's configuration and restart it. One bridge serves every
  forge it lists, each with its own space and name, and its
  [`docs/adding-a-forge.md`](https://github.com/sgo/forgelet-bridge/blob/master/docs/adding-a-forge.md)
  is the whole runbook.

The forge side is deliberately small, because the bridge reads the forge rather
than the other way round. A running dashboard, found through
`<forge root>/.swarmforge/dashboard-url` and read fresh every time. The endpoints
the bridge calls — `/api/state`, `/api/chat`, `/api/approvals/<id>/<action>`,
`/api/tasks/retry`, and `/api/clarifications/<id>/answer` — which mean a forge
whose tooling predates one of them is refreshed first, or left out. And the
forge's own adapter, `swarmforge/scripts/matrix-bridge.sh`, which arrives with
the bridge's installation rather than with this fork.

Getting the kit into a forge is the bridge's own step, `install-kit` and
`install-rules` on the adapter. Its paths default to a bridge that lives *inside
the forge it serves*, so there are two shapes:

- **The forge hosts the bridge.** Clone the bridge into `<forge root>/projects/`
  and build it with its `scripts/build.sh` — the defaults then point at that
  build, and a clone that was never built is one the adapter cannot start — and
  the install is two commands:

  ```sh
  root="<forge root>"
  cd "$root"
  ./swarmforge/scripts/matrix-bridge.sh install-kit
  ./swarmforge/scripts/matrix-bridge.sh install-rules
  ```

- **A bridge is already running for other forges.** Point the adapter at that
  bridge's build, at its kit and its rules, and at the forge being served:

  ```sh
  bridge="<bridge repository>"
  root="<forge root>"
  MATRIX_BRIDGE_FORGE_ROOT="$root" \
  MATRIX_BRIDGE_KIT_BINARY="$bridge/build/acceptance/bin/install-kit" \
  MATRIX_BRIDGE_KIT="$bridge/swarmforge/scripts" \
  MATRIX_BRIDGE_RULES_BINARY="$bridge/build/acceptance/bin/install-rules" \
  MATRIX_BRIDGE_RULES="$bridge/rules" \
  "$bridge/scripts/matrix-bridge.sh" install-kit
  # and the same again with install-rules
  ```

Either way the forge gains the route gate, the idler check, the stall watch and
its agent, the doorbell, and the rules a bridge's rooms rely on. The kit
installer also writes the watch's agent, so install into a forge you intend to
keep rather than a throwaway one.

## Products

| Command | Branch | Shape |
|---|---|---|
| `get-swarm-forge two-pack` | [`two-pack`](https://github.com/unclebob/swarm-forge/blob/two-pack/README.md) | Pack installed into the current project: `coder` → `cleaner`. |
| `get-swarm-forge four-pack` | [`four-pack`](https://github.com/unclebob/swarm-forge/blob/four-pack/README.md) | Pack installed into the current project: `specifier` → `coder` → `refactorer` → `architect`. |
| `get-swarm-forge six-pack` | [`six-pack`](https://github.com/unclebob/swarm-forge/blob/six-pack/README.md) | Pack installed into the current project: six separate specification, implementation, cleanup, architecture, hardening, and QA roles. |
| `get-swarm-forge project-manager` | [`project-manager`](https://github.com/unclebob/swarm-forge/blob/project-manager/README.md) | Multi-project forge with selectable two-, four-, and six-pack templates and a host lieutenant. |
| `get-swarm-forge lieutenant` | [`lieutenant`](https://github.com/unclebob/swarm-forge/blob/lieutenant/README.md) | Multi-project forge with one configurable project template and a planning lieutenant. |

A **pack** is composed into an existing project. Running `./swarm` starts that
project's configured roles.

A **forge** is installed into an empty host directory. Running `./swarm` starts
the forge dashboard and host lieutenant; project swarms start when the operator
creates or opens projects beneath `projects/`.

The selected product's README describes its routes, roles, worktrees, project
lifecycle, and dashboard behavior, and its branch configuration — not this
README — is the authority for current backend assignments and topology.

## Prerequisites

- `zsh`
- `git`
- `tmux`
- Babashka (`bb`)
- A Go toolchain, to build the bridge
- At least one configured agent backend: `grok`, `codex`, `claude`, or
  `copilot`

## For the rest of SwarmForge

Upstream's README on `main` is the authority for what SwarmForge itself is: the
configuration grammar, what the composition writes and when, the shared articles
and the role prompts that read them, and the runtime components and generated
state under `.swarmforge/`. Nothing here replaces it; this page is what a
Forgelet forge's operator needs on top of it.
