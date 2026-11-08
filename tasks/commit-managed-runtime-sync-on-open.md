# commit-managed-runtime-sync-on-open

# Commit managed runtime synchronization when opening a project

Opening a project refreshes its shared SwarmForge runtime files, but leaves them
as uncommitted tracked changes. This caused a live queue failure in
`forgelet-host`: `stage complete` requires a clean tree, so the agent stashed the
queue-aware runtime changes and ran the older committed `stage.bb`, which marked
the card done without promoting the queued card.

Make project refresh commit only the files it owns, as part of the closed-to-open
lifecycle and before starting the new runtime.

Implementation contract:

- In `swarmforge/scripts/forge.bb`, capture the exact managed paths changed by
  shared-script copying, shared-article copying, single-agent role bootstrap and
  agent-hook installation.
- Stage only those paths and create one managed commit with a stable message,
  for example `Sync SwarmForge runtime`, and a deterministic non-personal Git
  identity.
- Do not include unrelated source, feature, task, runtime-state or user changes,
  even when they are already staged. Use path-limited commit semantics that
  preserve the user's index/worktree.
- Add a narrow exception in `agent-pre-commit.sh` for this managed sync where
  every staged path is on the runtime-owned allowlist and the commit is uniquely
  identifiable. Never use `--no-verify`; an ordinary agent commit containing any
  non-runtime path still follows the approved-spec gate.
- Commit before `start-project-runtime!`. If the sync commit fails, do not start
  the runtime or mark the project open. Return an actionable error naming the
  runtime sync failure and preserve the working tree.
- Skip no-op commits when contents are unchanged. Handle the source layer project
  explicitly so opening it cannot misclassify source edits as generated runtime
  copies. Refuse safely for detached HEAD or other Git states that cannot accept
  the managed checkpoint without risking unrelated work.

Regression coverage must include clean and dirty projects, unrelated staged and
unstaged changes, changed scripts/articles, generated role/hook files, an
unapproved agent spec, pre-commit refusal, detached HEAD, idempotent reopen, and
the real queue case where `stage complete` runs after unrelated work was stashed
and restored. It must prove the queue-aware `stage.bb` is in HEAD when completion
runs.

Run all supported layer gates and the upgrade exercise. Update the layer
operator documentation and compose only after reviewing the managed path
allowlist. This card does not change queue ordering, gate semantics or project
source.
