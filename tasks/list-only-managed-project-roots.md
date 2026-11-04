# list-only-managed-project-roots

# List only managed project roots

## Operator-observed problem

After opening a forge in the desktop app, the project picker lists directories
that are not projects. In Saibill, Innersource and Ledgerhall it shows
`forgelet-bridge`, but those entries are only stale bridge build/rules
directories with no project identity. Forgelet does have a real
`forgelet-bridge` project, so that one must remain visible.

Saibill also exposes its `target` directory through the same behavior.

## Desired behaviour

- `all_projects` includes a directory only when that directory is a managed
  project root.
- Recognise `.swarmforge/single-agent`, `.swarmforge/pack`, and the legacy
  `.swarmforge/roles.tsv` identity.
- Preserve project order and the existing `open_projects` state.
- Do not change project directories or delete stray build output from this
  card.
- The desktop picker should need no special-case filtering.

## Boundaries

- Change the shared layer's project listing in `forge.bb`.
- Do not special-case `forgelet-bridge` by name.
- Do not modify Saibill, Innersource or Ledgerhall project source.
- Preserve the current runtime-support ownership work in progress.

## Acceptance

- A regression creates:
  - a real single-agent project;
  - a real pack project;
  - a legacy roles-only project;
  - a stale `forgelet-bridge` runtime/build directory;
  - an artifact directory.
- Only the three real managed projects appear in `all_projects`.
- The real `forgelet-bridge` project continues to appear in a forge where it
  has project markers.
- Existing forge list, open-project, stage and dashboard tests remain green.
- Layer tests and CRAP pass, and the change is committed and pushed to
  `forgelet`.
