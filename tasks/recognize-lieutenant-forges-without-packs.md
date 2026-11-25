# recognize-lieutenant-forges-without-packs

# Recognize lieutenant forges without a packs directory

## Operator-observed problem

The tic-tac-toe runtime upgrade installed the current runtime successfully, but
the final reopen phase failed with `start-forge-failed`. Starting the upgraded
forge reported:

```text
Error: No windows defined in <forge>/swarmforge/swarmforge.conf
```

The forge config is valid:

```text
lieutenant claude
```

The current `project-manager`/lieutenant composition stores its product
template under `.swarmforge/project-pack` and does not create a top-level
`packs/` directory. `swarmforge.bb` uses the `packs/` directory as evidence that
the root is a lieutenant forge. It therefore misclassifies the valid modern
forge as a pack with no window rows.

An empty `packs/` directory makes the current code take the correct branch, but
that is a compatibility accident, not a lifecycle marker.

## Desired outcome

- A valid lieutenant-only forge with `.swarmforge/project-pack` is recognized
  without requiring `packs/`.
- A legacy lieutenant forge with top-level `packs/` still runs.
- A root with neither marker fails closed with a bounded error.
- The same shared predicate controls top-level dispatch, config parsing, health
  checks and any other pack-versus-forge branch.
- Runtime upgrade can reopen a newly created modern forge without adding an
  empty directory or bypassing startup checks.

## Plan

1. Add one lifecycle-aware predicate for a lieutenant forge root:
   `.swarmforge/project-pack` is the modern marker; top-level `packs/` is
   accepted only for legacy compatibility.
2. Use the predicate from top-level dispatch and config parsing rather than
   checking `packs/` independently in each path.
3. Audit related health and start commands for the same implicit assumption.
4. Keep the valid `lieutenant <agent>` config requirement and existing window
   validation for pack compositions.
5. Add a fixture that upgrades a modern lieutenant forge while all projects are
   closed, then starts it and proves dashboard/Lieutenant readiness.

## Testing and evidence

- A modern lieutenant root with `.swarmforge/project-pack` and no `packs/`
  starts and reports healthy.
- A legacy lieutenant root with `packs/` continues to start.
- A root with neither marker fails closed without starting a pack accidentally.
- The lieutenant-only config must still name a supported agent.
- Runtime upgrade reaches `current`, starts the updated forge, and reopens the
  original project set in a fixture.
- Existing pack, project session, health and lifecycle tests remain green.

## Boundary

- Do not solve this by requiring an empty `packs/` directory.
- Do not change project template or runtime composition layout in this card.
- Do not relax configuration validation or launch unsupported agents.
- Do not touch another forge while testing.

## Verification

One lifecycle predicate now recognizes `.swarmforge/project-pack` as the modern
Lieutenant marker and `packs/` as legacy compatibility. Config parsing, role
restart, enclosing-project discovery, top-level dispatch, health, project
open/close and runtime stop use that predicate. A lieutenant configuration with
neither marker fails with bounded guidance before creating runtime sessions or
a dashboard.

Actual startup tests passed for both modern and legacy Lieutenant roots, with
the modern root containing no `packs/` directory and both roots reporting
`healthy`. Unsupported lieutenant agents still fail validation.

The full `bb test` gate passed 535 tests with 4,292 assertions, all 20 dashboard
browser tests, and the bounded-projection E2E; the detached lifecycle E2E
completed in 1,489 ms. The `bb crap` clean gate passed 24 tests with 132
assertions and wrote `target/coverage/lcov.info`. The documentation contract
passed 3 documents and 5 sections.

A real Host-created disposable modern Family had `.swarmforge/project-pack`,
no `packs/`, reported `healthy`, and was immediately `current`. To exercise the
reported upgrade path, its installed runtime was returned to the previous
`7ffd25e` `swarmforge.bb` identity and status reported one pending update. The
normal runtime upgrade then reached `current` at
`47fed48ece5a768334eb753b63a2d41309faee48`, started the updated modern Family,
and Host reported it reachable with no synthetic `packs/` directory. The
disposable Family was removed through Host and its directory moved to Trash.

## Operator acceptance

None.
