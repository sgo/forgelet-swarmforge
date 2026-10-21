# Single-agent project

This project is owned by one agent. It has a `stages.edn` and a `STAGES.md`
instead of pack roles and handoffs. `./swarm` starts the local runner; the
runner serves `pack_web` for this project and gives the agent one dashboard
request at a time.

The forge dashboard marks this project as single-agent and links to the
project's own dashboard.

## Snapshot deployment

A completed card deploys its current `-SNAPSHOT` after every quality gate and
after its reviewed commit has been pushed:

```sh
stage check deploy
```

The stage runs `scripts/publish-snapshot.sh` through
`GITHUB_PACKAGES_TOKEN` and the `with-release-tests` profile.
`swarmforge/deploy.conf` names the target, and `stage complete` refuses while
the required deploy stage is absent or stale. The optional `release` stage is
separate.

## Acceptance

The approved Gherkin is executed, not just translated into hand-written tests.
`acceptance/src` carries the parser IR reader, the step dispatcher, and the
per-scenario state. Add a `StepHandler` for every step shape; each handler
calls the project's public API.

```sh
scripts/run-acceptance-tests.sh
```

The script compiles the harness separately from the Maven modules, so it works
for a single module or a reactor. It writes `target/acceptance/acceptance.log`
and fails on an unhandled or ambiguous step.

Gherkin mutation runs in `harden`:

```sh
scripts/run-acceptance-mutation.sh
```

It mutates example values through the same runner and writes
`target/acceptance-mutation/mutation.log`. The mutator rewrites features with
its manifest, so the script works on copies under `tmp/`; approved feature
files are untouched.

Projects with custom value formats add `acceptance/value-adapter.sh`; the
mutation script passes it as `--value-adapter`. The adapter and the step
handlers must share the same parser, so a mutated value is still executable.
