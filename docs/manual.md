# Forgelet layer manual

This manual describes how the shared layer is composed and upgraded. The
executable contracts live in the layer test suite; this page explains the
operator-visible model.

## Composition

`get-swarm-forge` downloads the selected product and this repository's
`forgelet` branch, then overlays the shared runtime onto the target forge. A
successful composition writes `.swarmforge/runtime-manifest.edn` with the
product, source commit and a SHA-256 digest over layer-owned files.

The composer resolves the layer branch once and downloads that exact commit;
all shared runtime files and the manifest therefore name one source revision
even if the branch advances during the run. A local development source must be
selected explicitly with `SWARMFORGE_GIT_DIR`, or supplied as one complete,
clean Git-backed `SWARMFORGE_BASE_DIR`. Those selectors are mutually exclusive.
The composition compares every layer-owned installed file with the resolved
source and fails before writing a runtime claim if any content differs.

`SWARMFORGE_SKIP_BRIDGE=1` omits bridge build and installation while preserving
an adapter already installed by the bridge.

## Runtime upgrades

`forge_runtime_upgrade.sh status` stages a disposable composition and reports
the available digest without changing projects. `forge_runtime_upgrade.sh apply`
rechecks that identity, refuses busy projects, applies project-template
upgrades, reopens the original projects and verifies health.

Runtime upgrades use the explicit `bridge_policy` reported in status and
operation records. The current `preserve-installed-bridge-v1` policy skips
bridge installation and keeps the installed adapter, bridge project,
configuration, state and rooms. A composer disagreement fails as
`bridge-policy-mismatch`; `compose-digest-mismatch` remains the guard for a
different composed runtime.

An upgrade does not grant permission to restart the independently managed
`forgelet-host` process.
