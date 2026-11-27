# Single-agent project

This is a language-neutral single-agent project. The operator's first card must
choose the implementation language and replace the placeholder lifecycle gates
before product implementation begins.

The project already has one selected agent, a task and feature location, shared
runtime tooling, Git, project status, and the runner entry point:

```sh
./swarm
```

The project language is not assumed. When the first card chooses it, write the
language to `swarmforge/language.conf` and establish that language's build,
test, mutation, acceptance, documentation, deployment and release gates.
