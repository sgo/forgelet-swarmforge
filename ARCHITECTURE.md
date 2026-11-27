# Forgelet layer architecture

The repository owns composition and the shared runtime input. It does not own
the running Host, bridge service, project state or rooms.

## Boundaries

`get-swarm-forge` is the composition boundary. It combines the selected product
with the layer and writes the runtime manifest. The `forge-runtime-upgrade`
helper is the lifecycle boundary: it checks and applies a published layer while
keeping the target forge's process and state policy explicit.

`forgelet-host` is independently managed. A forge runtime upgrade may stop and
start its own dashboard and project sessions, but it does not own or restart
the Host process.

Bridge-owned files are transitional. Upgrades preserve the installed adapter,
bridge project, configuration, state and rooms while runtime updates continue
for files the layer owns. The runtime digest still hashes every composed runtime
file, and preview/apply must agree on that set.

The default single-agent project template owns execution structure, not
language. `generic` supplies project identity, Git, messaging, feature/task
locations and fail-closed stage placeholders. The explicit `java-maven` starter
owns the Java language choice and its build-specific files. Template
composition, the versioned composer cache, and upgrades keep those concerns
separate.
