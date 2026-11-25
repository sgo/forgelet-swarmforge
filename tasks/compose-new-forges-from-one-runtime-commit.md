# compose-new-forges-from-one-runtime-commit

# Compose new forges from one runtime commit

## Operator-observed problem

The newly created `tic-tac-toe` Family immediately reported **Upgrade available**.
Its runtime manifest recorded source commit `42ea5c9`, but three installed
shared files were from an older runtime:

- `swarmforge/constitution/articles/stopping-without-finishing.prompt`
- `swarmforge/scripts/doorbell.bb`
- `swarmforge/scripts/role_health.bb`

A later application of the normal runtime upgrade corrected them. A new Family
should not require an immediate repair upgrade.

## Current evidence

- The installed digest was
  `sha256:8e1c386eda5e28889a2b44b0d62af65cb45072ae1995625b18c5bb79019dc6a2`.
- The current published digest is
  `sha256:e029012a720fb7d08ecc5909437dc23ab997aad216e75b3ff7bb41caf88a6b1f`.
- Both manifests recorded source commit
  `42ea5c99255e59dd8a9db473ec817139c2a27f04`.
- The three stale installed files match the older checked-in forge runtime.
- A disposable clean-environment composition through the same
  `get-swarm-forge` helper installed the current bytes for those three files.

The composer resolves the source commit, consumes mutable branch archives and
writes the manifest as separate operations. It therefore does not prove that
the installed files and recorded commit came from one exact source revision.
Ambient composition overrides make the mismatch more likely.

## Desired outcome

Family creation composes all shared runtime files from exactly one canonical
published layer commit and records that same commit in the installed manifest.
A successful new Family is observationally `current` for the published runtime
immediately after creation. A mismatch fails closed before the Family enters the
catalogue and removes the partial destination.

## Plan

1. Resolve the canonical `forgelet` layer commit once at composition start.
2. Fetch the shared layer for that exact commit rather than separately resolving
   and consuming a mutable branch archive later.
3. Carry the resolved commit through composition and write the runtime manifest
   from that same identity.
4. Make source-selection overrides explicit. Either reject conflicting
   `SWARMFORGE_BASE_DIR`, `SWARMFORGE_GIT_DIR`, branch, repository and pack
   overrides for ordinary creation, or resolve them before copying any file.
5. Add a final composition identity check so mixed source content cannot be
   written as a successful manifest.
6. Verify an ordinary disposable Family through the Host is immediately
   `current`, without running the upgrade action afterward.

## Testing and evidence

- A clean composition records the resolved commit and current file hashes.
- A branch that advances between accepted request and completion cannot switch
  part of the installed runtime to the newer commit.
- A stale or unexpectedly cached branch archive cannot produce a manifest that
  claims a different source identity.
- Conflicting ambient override variables either fail with bounded guidance or
  resolve to one declared source identity before mutation.
- The runtime digest and bridge-policy checks remain strict.
- The full layer suite, dashboard tests and clean gate remain green.
- Host rollout acceptance creates one disposable Family and immediately sees
  `current` for the published runtime.

## Boundaries

- Do not weaken runtime identity or bridge-policy validation.
- Do not auto-upgrade a partially created Family and report the original create
  operation as successful.
- Do not change project-template upgrade behavior in this card.
- Preserve the existing option for deliberate development compositions from a
  declared local source, but make that selection explicit and consistent.
- Do not alter an existing healthy forge as part of source verification.

## Verification

The composer resolves the layer commit once, fetches that exact SHA, carries it
into the manifest, and compares every layer-owned installed file with the
resolved source before writing the runtime claim. Conflicting
`SWARMFORGE_BASE_DIR`/`SWARMFORGE_GIT_DIR` selectors and an uncommitted local
runtime fail before target mutation. A branch-advance fixture proves that the
product fetch cannot switch the installed runtime to the newer layer commit.
Bridge installation is followed by a byte-for-byte restore of the layer runtime
while the installed adapter and bridge state are preserved.

The full `bb test` gate passed 531 tests with 4,273 assertions, all 20 dashboard
browser tests, and the bounded-projection E2E; the detached lifecycle E2E
completed in 1,506 ms. The `bb crap` clean gate passed 24 tests with 132
assertions and wrote `target/coverage/lcov.info`. The documentation contract
passed 3 documents and 5 sections.

A real Host provisioning operation created a disposable Family from published
commit `586a97d0c451d27efedcaa33ae64868ffec96540`. Its manifest and runtime
status both recorded that commit, installed and available digests matched at
`sha256:89b2531c7cb5752eb9e6771b85eabdf3550c18f87a629ba07c3965fe61684834`,
and status was immediately `current` with no changes, blockers or project
upgrades. The three formerly stale files matched the published source bytes.
The Family was reachable with no repair upgrade; Host removal completed and the
disposable directory was moved to Trash.

## Operator acceptance

None.
