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

## Contents

- [The Forgelet layer](#the-forgelet-layer)
  - [Install the helper](#install-the-helper)
  - [Start a forge](#start-a-forge)
  - [Where your own things go](#where-your-own-things-go)
  - [Update a forge](#update-a-forge)
  - [Hook the forge into a bridge](#hook-the-forge-into-a-bridge)
- [Products](#products)
- [Prerequisites](#prerequisites)
- [For the rest of SwarmForge](#for-the-rest-of-swarmforge)

**Manuals.** A manual lives with whatever owns the thing it describes: the family's own are
in this repository's `docs/`, a project's are in that project's `docs/`, and a forge's are in
that forge's — so the reading copy comes with the values filled in.

- [What a project owns](docs/what-a-project-owns.md) — the files a project provides rather
  than receives: `swarmforge/language.conf`, `swarmforge/deploy.conf`,
  `swarmforge/hooks/card-complete.sh`, its local articles and helpers, and what reads each of
  them.
- [Adding a forge](https://github.com/sgo/forgelet-bridge/blob/master/docs/adding-a-forge.md)
  — the bridge's runbook for putting a forge on the operator's phone.
- that runbook filled in for one forge lives in that forge's own `docs/`, with its values,
  checks and decisions — filled-in copies stay with the forge they are about.

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

Most of what a Forgelet forge needs comes from `forgelet`. The two packs this
family carries its own copy of come from forks of their own, in the same shape as
this repository: `forgelet-six-pack` and `forgelet-four-pack`, each with `main`
mirroring the upstream pack branch and `forgelet` holding ours - one rule, that a
finished card does not ask the operator for the next one, beside everything
upstream's. A pack with no fork of its own comes from this repository's branch of
the same name, as it always has, and the helper's `SWARMFORGE_*_PACK_REPO` and
`SWARMFORGE_PACK_REF` overrides point it elsewhere if a forge wants Uncle Bob's
packs untouched. Anything upstream should have for its own sake is offered there
first as a pull request; the branches here carry it until it lands, and both
`main` and `forgelet` stay rebaseable onto upstream with no Forgelet history in
the way. `main` is the landing page, installer source, shared runtime, and shared
engineering law — it is not itself a runnable SwarmForge product.

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

### Single-agent projects

A forge can hold a project owned by one agent instead of a pack. From a layer
checkout:

```sh
./get-agent-project <name> --forge <forge-root>
```

It composes `stages.edn`, `STAGES.md`, a one-row `roles.tsv` and the
`.swarmforge/single-agent` marker into `projects/<name>`. Open the project from
the forge dashboard: the row is marked single-agent and links to the project's
own `pack_web` dashboard. `./swarm` starts the runner, which takes one dashboard
request at a time, pastes it into the project's persistent `swarmforge-agent`
tmux session, and appends `stage list` to the answer. The same session is what
`forge-attach <forge> <project> agent` opens, so the phone and the laptop use
one agent rather than two. The command defaults to
`codex -C <project> --no-alt-screen --dangerously-bypass-approvals-and-sandbox`;
set `SWARMFORGE_AGENT_COMMAND` to use another agent. The runner never creates
a worktree or a handoff. The attached tmux window is named for the project,
with automatic renaming disabled, so its status line reads
`swarmforg0:<project>` rather than `swarmforg0:codex`.
Closing the project stops both the relay and the `swarmforge-agent` session;
`agent-runner.bb --stop-agent` stops only the agent, leaving the phone relay
running.

An existing single-agent project can adopt structural additions from the
current template with:

```sh
get-agent-project --upgrade <project-name-or-path> --forge <forge-root>
```

`--check` reports what is missing without changing files. The upgrade adds
only missing template structure: it preserves the project's source, tasks,
features and existing stages, inserts a new required stage in its template
position, and refreshes only managed helper files. It then prints the
project-local work required to make the new gate pass.

A six-pack project can be converted in place with:

```sh
get-agent-project --convert <forge-root>/projects/<name> \
  --forge <forge-root> [--profile standard|library]
```

The default is a dry run: it checks the project and reports the worktrees,
branches, pack runtime and cards it will handle. `--apply` performs the
conversion. The project must be a direct child of `<forge-root>/projects`, closed
through that forge, and on a clean primary checkout. Every linked worktree must
be clean, attached, under `.worktrees`, and already merged into the primary
branch. The converter also refuses a live handoff daemon, project dashboard or
tmux socket. The target forge must carry the branch-agnostic single-agent
runtime from layer commit `f5813a0`; an older forge is refused with an upgrade
instruction rather than producing a mismatched project.

Conversion keeps the source, Git history, tasks, features, QA material, local
scripts and completed board rows. It archives the old pack metadata, roles,
daemon/dashboard/stage state, session files, configuration and completion hook
under `.swarmforge/archive/pack-conversion-<timestamp>/`, then removes the
merged worktrees and their branches. Active board rows move to `spec` with their
names and task IDs unchanged; done rows stay in `done`. It installs the
single-agent marker, one-agent roles, current `stages.edn` profile, shared
scripts, instructions, documentation/e2e contracts and hooks. The one-agent
role row names the project's actual checked-out branch, so projects on `main`,
`master` or another branch retain that branch without a rename.

Review the resulting tree and open the first real card before committing the
conversion. The single-agent pre-commit hook requires that card's spec to be
proposed and approved, so conversion does not create a fake approval or bypass
the hook with `--no-verify`.

Single agents commit the approved spec before implementation and then make
coherent checkpoint commits as work lands. They do not wait for every gate or
deploy before the first commit. The pre-commit hook checks only the approved
spec; stage evidence is tied to the Git tree, so committing unchanged contents
does not invalidate a gate that already passed. The upgrader installs this
policy into existing projects' `AGENTS.md` and `STAGES.md`.

When a routed card exists on the single-agent board, `stage propose spec`
binds that card's name and task ID into the proposal and carries the same board
row through every stage. Older project-named placeholder cards are removed
when the routed card is bound; they are not the work card.
Single-agent specs are automatically approved by default: the agent records the
task note, Gherkin and spec-stage evidence, then proceeds to implementation
without an approval request. A forge can put `spec=operator` in
`swarmforge/local-spec-approval.conf` to restore explicit first-spec approval
for all of its single-agent projects. In that mode, corrections to the same
unfinished card remain automatically approved after the first operator
decision. Pack projects always retain their existing operator gate.
The standard profile also carries a required card-specific `e2e` stage after
`qa`. Cards register their procedure and script in `qa/e2e.edn`; declared input
names must be present at run time, missing values fail rather than skip, values
are redacted from evidence, and a card without an applicable procedure needs an
approved task-note waiver.

The spec stage is the specifier's work: the agent turns the operator's card
into `tasks/<name>.md` and `features/<name>.feature`, validates the Gherkin,
and records the feature for approval under the forge policy. The operator does
not fill a template.

The project carries an `AGENTS.md` that points at `STAGES.md` and the forge's
copied `swarmforge/constitution/articles/engineering.prompt`, so the build,
quality, release and deployment rules travel with the project rather than
being repeated in every operator request.
The required `docs` stage runs `scripts/check-documentation.sh`. The maintained
README, consumer manual and architecture document are linked through
`docs/manual-contract.edn` to public APIs and executable examples, so changed
behaviour makes the owning section fail instead of leaving stale prose in the
tree.
The template's required `deploy` stage runs `scripts/publish-snapshot.sh` after
the quality gates. It deploys the current `-SNAPSHOT` through the configured
Maven target, so `stage complete` cannot finish the card while the snapshot is
unpublished or the deployment stage is absent, optional or stale.

For a library, pass `--profile library`: the template replaces the acceptance
QA gate with an explicit `verify` stage (property tests, plus the library's API
compatibility check). That is a deliberate profile, not an `n/a` that hides a
skipped gate.

The bridge gives each single-agent project a room inside the forge's space. A
forge needs a bridge build carrying that slice (`f95b54a` or later).

### Where your own things go

Composition writes the paths it names and nothing else, so everything in this
table survives every update with nobody doing anything. Each is either
`local-`-prefixed or sits beside the shared file it belongs to, so the next
session — and the next forge of the family — can see it is ours.

| Yours | Where it goes |
|---|---|
| A helper script for this forge | `swarmforge/local-scripts/`, which is on every session's `PATH` |
| Rules for this forge's lieutenant | `swarmforge/roles/local-lieutenant.prompt`, read after the shared lieutenant prompt |
| Single-agent spec approval policy | `swarmforge/local-spec-approval.conf`, with `spec=operator` to opt out of automatic approval |
| A project's language | the project's own `swarmforge/language.conf`, and nothing else decides it |
| Rules for one project | that project's `local-*.prompt` articles, beside the shared articles |
| What runs when one of its cards lands | that project's `swarmforge/hooks/card-complete.sh` |
| How this forge is configured | the forge's `swarmforge/swarmforge.conf` |

What is *not* yours is the shared runtime: `swarmforge/scripts/` is replaced
wholesale on every update, which is why a helper looks like a sibling of it
rather than a file inside it.

The project-facing half of that table has a manual of its own:
[what a project owns](docs/what-a-project-owns.md) — what each file is for, what reads it,
and what to do when it is missing.

### Skills the layer ships

The layer carries agent skills under `swarmforge/skills/`, one directory per skill holding a
`SKILL.md`. Composition writes them into every forge it builds and replaces only the skills the
layer carries, so a forge's own — anything it keeps in `swarmforge/skills/` that the layer does not
ship — survives an update. The shared skills are the layer's to change; a forge's extras are the
forge's.

Codex reads skills from `~/.codex/skills`, not from the forge tree, so a composed skill reaches a
role only once it is linked there. The dev box's provisioning does that (`devbox/skills/link.sh`,
from `sgo/forgelet-forge`), and a running session picks a new link up the next time it starts.

### The agent's command shell, and where the helpers come from

A Codex agent's command shell can be served by its app-server daemon, which hands the shell its
*own* environment rather than the role's. The symptom is specific and misleading: the agent process
has the forge's helper directories on `PATH` (visible with `ps eww`), while the shell answers
`ready_for_next.sh: command not found`, and a role that works around it by prepending the path to
every command is hiding the fact rather than reporting it. The daemon outlives the role, so a
machine that worked at one session can fail at the next with nothing changed in the tree.

Two mechanisms in this layer make a tree's helpers reachable whichever process spawns the shell,
and both derive the tree from the working directory rather than from anything inherited:

- the shell profile adds `$PWD/swarmforge/local-scripts`, `$PWD/.swarmforge/bin` and
  `$PWD/swarmforge/scripts` when the working directory is a forge tree, for shells that read the
  profile at all;
- small dispatchers linked into `~/.local/bin` — a directory the daemon's environment does carry —
  resolve the named helper from the shell's current directory, and say so plainly when a shell is
  not in a forge tree. They are installed by `devbox/forge-helpers/install.sh` from `sgo/forgelet-forge`,
  which the dev box's bootstrap runs and checks. A shell already holding its tree's directories on
  `PATH` finds the real script first; the dispatchers are the fallback for shells that do not.

Because both are per-directory, a worktree, a project root and a forge root each reach their own
scripts, and no session can reach another forge's.

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
