---
name: convert-pack-project-to-single-agent
description: "Plan, perform or review an in-place conversion of an existing SwarmForge multi-agent pack project into a single-agent project. Use when removing roles, worktrees, handoffs or pack completion machinery while preserving the project's source, history, gates and project-owned tooling; do not use for creating a new single-agent project or for ordinary single-agent card work."
---

# Convert a pack project to one agent

Conversion removes coordination between roles. It must not silently remove the
project's ability to build, verify, accept, document, publish or complete work.

Obtain the owning operator's authorization, close the project through its forge,
and preserve unrelated work. The project belongs to its forge; perform the
conversion where that forge's own tooling can see it.

## Conversion workflow

1. Identify the forge root, project root, current branch, remote default branch
   and the operator-visible outcome. Do not assume `master`.
2. Audit the project before changing it. Read
   [references/conversion-audit.md](references/conversion-audit.md) and record:
   build modules, acceptance runner, QA procedures, deploy target, completion
   hook, project-owned rules, worktrees and generated runtime state.
3. Check every linked worktree is clean. For an unmerged branch, determine
   whether its patches are already present or its final tree differs. Never
   guess and never discard a branch whose content differs.
4. Run the converter as a dry run:

   ```sh
   get-agent-project --convert <project-root> --forge <forge-root>
   ```

   The default standard profile is for an application. Use
   `--profile library` only when the public API, not a user-facing end-to-end
   path, is the product.
5. Review the reported project-specific gaps before applying. The generated
   template is a starting point, not proof that its commands fit this project.
6. Apply only after closure, cleanliness and preservation checks pass:

   ```sh
   get-agent-project --convert <project-root> --forge <forge-root> --apply
   ```

7. Adapt the converted project before handing it the first real card. Complete
   the checks below.
8. Run the project's real gates and the single-agent structural check. Commit
   the conversion as its own checkpoint after the first card's current spec is
   approved, before implementation changes. Never use `--no-verify`.

## Gates must be real

Inspect `stages.edn` against the project rather than accepting template defaults:

- Replace Maven module paths that do not exist.
- Point architecture at the project's real module or script.
- Point documentation at the documents that exist and are maintained.
- Point deploy at the project's real publisher, or remove a configured target
  deliberately.
- Preserve build profiles and pin the project's actual Java/tool versions.
- A disabled or waived gate needs an explicit reason in the approved task note;
  do not make a gate optional merely to make the first card pass.

Read [references/testing-stacks.md](references/testing-stacks.md) before changing
property, mutation, CRAP, DRY or release checks. A gate that exits zero after
running zero checks is broken.

## Preserve project-owned machinery

Use the pre-conversion audit as the source of truth:

- Keep one working acceptance setup. If the project already has a Gherkin runner,
  wire the single-agent stage to it; remove a newly added empty template runner
  only after comparing evidence.
- Preserve `privacy.prompt` and, for libraries, contract-stability rules. Remove
  only handoff/workflow articles that describe roles no longer present.
- Preserve project-local rules, scripts, manuals, QA procedures and deployment
  helpers.
- Inventory `qa/*.md` and `scripts/run-qa-*.sh`; classify standing repository
  regression separately from operator checks that need an account, home,
  native build or pseudo-terminal.
- If the project needs a card-completion hook, install or adapt one that pushes
  the checked-out branch without force, publishes only a configured snapshot,
  skips unchanged snapshot inputs, and reports "pushed but not published" on
  failure. Test it in a throwaway clone.
- Keep the current branch and remote default; never rename `main` to `master` or
  encode a branch name the project does not have.

## Verification before the first card

Require all of the following:

- The converter's archive and manifest are present.
- Active board cards retain their task IDs; completed cards remain complete.
- The selected profile has the intended order of spec, build, quality,
  documentation, deploy and optional release stages.
- Every command and evidence path exists or is produced by a real run.
- Existing source, history, features, tasks, QA, documentation, local scripts
  and project-owned rules survive.
- The pre-commit hook accepts only the current approved spec.
- `get-agent-project --upgrade <project> --check` is clean.
- No old pack daemon, dashboard, role session or linked worktree remains live.

Report anything intentionally left manual. The conversion is not complete merely
because the file moves succeeded.
