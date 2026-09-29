# What a project owns

A forge composes projects; a project is the repository its agents work in. Most of what an
agent reads is not the project's own: the shared runtime (`swarmforge/scripts/`) and the
shared articles come from the layer, through the forge, and the pack brings the roles and
the rest of the articles. This is the other half - the files a project owns, what reads
them, and what happens when one is missing.

| the project's ... | lives at | what reads it |
|---|---|---|
| its language | `swarmforge/language.conf` | the engineering article's language table, and every rule that is written per language |
| its deploy target | `swarmforge/deploy.conf` | whatever finishes a card, and the release steps |
| what happens when a card finishes | `swarmforge/hooks/card-complete.sh` | the tooling, once per card |
| its own rules, beside the shared ones | `swarmforge/constitution/articles/local-*.prompt` | every agent, every session |
| its own helper scripts | `swarmforge/local-scripts/`, on every session's `PATH` | the roles that call them |
| its roles, their agents and worktrees | `swarmforge/swarmforge.conf` | the launcher |
| what the project is for | `mission.md` | the agents, when they need the why |

## `swarmforge/language.conf` - what the project is written in

One word - `Java`, `Kotlin`, `Go`, `Clojure`, `Babashka` - and it is the *only* thing that
decides which language rules apply. The engineering article's table is written per language,
as are the sections beside it (`## Java Gate Tasks`, `## Java Releases`, `## Kotlin Gate
Tasks`), so this file is what puts a Java project on the Java rules.

Nothing else decides it: not the layer, not the pack the project was composed from, not any
template. It is written once - a project re-languaged by hand keeps its answer through every
refresh and update - and a project that does not have one has not said what it is written in
yet. Ask the operator and write the answer here rather than assuming.

## `swarmforge/deploy.conf` - where the project publishes

The gate, not the deploy: it names a deployment target (this family uses `github-packages`),
and everything that deploys reads it first. Absent or empty means *do not deploy*, which is
the right state for a project that ships nowhere.

What the deploy itself is depends on the project: its build, its artifact, the credentials
it needs. That is why the file names a target rather than a command, and why the deploy
steps live with the project - a token helper under `scripts/`, Maven settings under `.mvn/`,
the `distributionManagement` in the `pom.xml` - instead of in the layer. A project with
`deploy.conf` and no way to deploy fails loudly when something tries; a project without
`deploy.conf` deploys nothing and is not asked to.

## `swarmforge/hooks/card-complete.sh` - what happens when a card finishes

The project's finishing step. The tooling decides *when* it is true; the project decides
what it means.

It runs once per card, and only when all three of these hold:

- it is the worktree that holds **master** - the other worktrees are roles receiving work,
  not the card finishing, so a coder or cleaner mid-pipeline can never trigger it;
- the card's board row already reads **`done`** - so the earlier copies of a card's work do
  not fire it, and neither does work still moving through the pack;
- the handoff that just merged brought **new** work in - so re-reading a task that is
  already in progress does not run it twice.

It is given the project root as its working directory and these in the environment:
`SWARMFORGE_EVENT` (`card-complete`), `SWARMFORGE_PROJECT`, `SWARMFORGE_TASK`,
`SWARMFORGE_COMMIT`, `SWARMFORGE_FROM`, `SWARMFORGE_ROLE`, `SWARMFORGE_HOOK`. It takes no
arguments and writes whatever it likes to stdout, which lands in the finishing role's pane.

What is expected of it: leave the tree as it found it apart from what it did; never force a
push, so a remote that has moved on is a failure rather than work overwritten; and exit
non-zero when it cannot do its job. The tooling prints `HOOK_FAILED card-complete <card>
(exit n)` and the role reports it to the operator - the card's work stays merged either way,
because the finishing step runs after the merge and never un-merges anything. A hook that is
present but not executable is ignored with `HOOK_IGNORED`, which looks like a working
finishing step and is not one.

What should be in it is the project's decision, and so is whether it exists at all: a project
without the file simply has no finishing step. Push the branch the card landed on, deploy
when `swarmforge/deploy.conf` names a target, publish something somewhere else - all of that
is the project's policy, which is why the layer ships no default. A release finishes without a
merge, so this hook does not run for one; a release does its own deploy and push deliberately.

## The rest of what a project owns

- `swarmforge/constitution/articles/local-*.prompt` - rules that are true of this project and
  no other. Every agent reads every article in that directory, so a local article is how a
  project adds to the shared law without editing it.
- `swarmforge/local-scripts/` - helpers small enough to live with the project. The shared
  scripts in `swarmforge/scripts/` are replaced wholesale on every update; this directory is
  never written by the composition, and it is on every session's `PATH`, so a helper goes
  here rather than inside the shared directory.
- `swarmforge/swarmforge.conf` and `mission.md` - the roles with their agents and worktrees,
  and what the project is for.
