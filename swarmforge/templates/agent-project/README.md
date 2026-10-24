# Single-agent project

This project is owned by one agent. It has a `stages.edn` and a `STAGES.md`
instead of pack roles and handoffs. `./swarm` starts the local runner; the
runner serves `pack_web` for this project and gives the agent one dashboard
request at a time.

The forge dashboard marks this project as single-agent and links to the
project's own dashboard.

## Documentation contract

Every completed card includes the `docs` gate. The maintained surfaces are:

- `README.md` for setup, coordinates, quick start and project status;
- `docs/manual.md` for detailed public behaviour; and
- `ARCHITECTURE.md` for boundaries and structural decisions.

`docs/manual-contract.edn` maps sections to the public APIs they explain and
to executable examples. `scripts/check-documentation.sh` checks the documents,
headings, API ownership, local links and example output. A section without an
example must be explicitly conceptual and carry its reason.

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

## End-to-end QA

The standard profile adds an `e2e` stage after `qa`. Register the active card in
`qa/e2e.edn` with its procedure, script and required environment-variable names,
then run:

```sh
scripts/run-qa-e2e.sh
```

The runner validates every named input, fails when one is absent, redacts input
values from captured output and writes `target/e2e/check.log`. A card whose
approved task note has an explicit `## E2E waiver` with a reason passes without
a card-specific entry.
