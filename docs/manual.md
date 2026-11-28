# Forgelet layer manual

This manual describes how the shared layer is composed and upgraded. The
executable contracts live in the layer test suite; this page explains the
operator-visible model.

## Composition

`get-swarm-forge` downloads the selected product and this repository's
`forgelet` branch, then overlays the shared runtime onto the target forge. A
successful composition writes `.swarmforge/runtime-manifest.edn` with the
product, source commit and a SHA-256 digest over layer-owned files.

Lieutenant composition puts the project template under
`.swarmforge/project-pack`. That marker identifies a modern Lieutenant Family
without requiring a top-level `packs/` directory. Legacy Families with
`packs/` remain valid, and a lieutenant-only configuration with neither marker
fails closed instead of being parsed as an empty pack.

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

Detached spec jobs cross the same spec-approval boundary as foreground stages.
Automatic single-agent approval is recorded before the job and advances the
active card through the normal `spec` to `code` transition. Operator approval
must already exist and match the current bundle. Approval failure occurs before
the job store or board can gain a launched spec job, and the detached job is
bound to the approved non-nil hash for reconciliation.

Runtime identity acceptance is hermetic and does not use a live Host or Matrix:

```sh
qa/run-runtime-identity-acceptance.sh
```

The script clones the explicit source commit locally and composes with bridge
installation disabled. It verifies the manifest source, all file hashes,
digest and bridge-policy evidence, then removes its complete fixture tree on
success, failure, timeout or interruption. Live bridge configuration, control
state, homeserver data and operator invitations are outside this boundary.
Real app/Host creation is a separately invoked operator acceptance check after
release.

## Single-agent project templates

`get-agent-project` defaults to the language-neutral `generic` template. The
`standard` and `library` profiles select work shape only: standard uses the
`qa` lifecycle placeholder and library uses `verify`. Neither selects a
language or build tool.

Generic creation contains no `swarmforge/language.conf`, POM, Gradle files,
Java acceptance source, snapshot installer or release configuration. Its
non-spec stages invoke `scripts/stage-placeholder.sh`, which exits nonzero with
instructions to choose a language, write `swarmforge/language.conf`, establish
the language's gates, and replace the placeholders. The `spec` stage is usable
immediately.

The explicit `--template java-maven` starter preserves the complete Java/Maven
scaffold, including its language choice and Maven-specific stages. The
installed composer cache includes both generic and Java/Maven template roots.
Upgrades preserve existing source and gates; Maven-specific additions follow
actual Maven evidence in the project rather than a default language choice.

## MCP-only project preparation

MCP-only stage execution remains disabled by default. A forge-owned
`swarmforge/mcp-only-projects.conf` may list exact single-agent project names;
`SWARMFORGE_MCP_ONLY_ALLOWLIST` can select a different file. For those projects,
fresh composition and explicit upgrade omit the local `stage.sh` and `stage.bb`.
Only those executables are removed: `stages.edn`, project scripts, task notes
and stage evidence remain, and runtime refresh keeps the helpers absent.
Projects outside the allowlist are unchanged.

The managed stage guidance block selects the MCP tool sequence only when
`.swarmforge/mcp/stage-grants.json` is a valid owner-only enabled grant for that
Family and project. Otherwise it records a clearly marked local-helper fallback.
The project pre-commit hook calls the shared `stage approval-status` interface
instead of treating a missing local helper as success. Missing, unreadable or
invalid approval state fails closed.
