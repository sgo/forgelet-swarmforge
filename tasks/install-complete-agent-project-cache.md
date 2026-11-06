# install-complete-agent-project-cache

# Install a complete agent-project cache

## Operator-observed problem

The app accepted `my-test-project` with agent `claude` but the host reported
that project composition failed. The failed host operation belongs to Saibill
at stage `composing`.

Reproducing the installed composer outside the app produced:

```text
get-agent-project: supported-agent definition not found:
/Users/sgo/.local/share/forgelet-swarmforge/current/swarmforge/scripts/agents.conf
```

## Cause

`devbox/get-agent-project/install.sh` builds a versioned composer cache from
the layer, but copies only the composer, converter, templates and three helper
scripts. The newly shared `swarmforge/scripts/agents.conf` is omitted from the
cache and from cache matching. The source tests invoke the layer checkout, so
they do not catch an incomplete installed cache.

The installer is also in the wrong repository: it currently exists only in
the `forgelet-forge` root, while the composer, templates and `agents.conf` it
installs belong to `forgelet-swarmforge`. This split ownership is why a layer
card could pass while its machine-wide installed command was incomplete.

## Desired behaviour

- The canonical cache installer lives in `forgelet-swarmforge` beside the
  composer and templates, on the `forgelet` branch.
- The forge/devbox bootstrap installs from the layer-owned copy rather than
  maintaining a second source of truth.
- The installed composer cache contains every file `get-agent-project`
  requires at runtime, including `swarmforge/scripts/agents.conf`.
- Cache validity and cache matching detect a missing or stale `agents.conf`.
- Reinstalling over an existing stale cache creates or selects a complete
  release.
- The installed `~/cmds/get-agent-project` can compose every supported agent.

## Acceptance

- The layer-owned installer preserves the existing wrapper and versioned-cache
  behavior while replacing the root-only installer as the source of truth.
- Install the layer cache into an isolated `FORGELET_AGENT_PROJECT_CACHE` and
  `HOME`-independent wrapper location.
- Run the installed composer from that cache for codex, claude, copilot and
  grok against a fixture Family; each creates the project and records the
  selected agent.
- Prove `install.sh --check` fails when `agents.conf` is missing or differs
  from the source layer.
- Keep the cache layout versioned and atomic.
- The forge/devbox bootstrap has no remaining second implementation of the
  installer.
- Layer `code` and `clean` stages pass on the committed `forgelet` tree.

## Live verification

After the source fix is committed and pushed, reinstall the cache, retry
`my-test-project` with Claude in Saibill, then verify:

- the project exists under Saibill's `projects/`;
- `.swarmforge/roles.tsv` records Claude;
- `mission.md` carries the submitted mission and is committed;
- the project opens and its agent session launches Claude rather than Codex.
