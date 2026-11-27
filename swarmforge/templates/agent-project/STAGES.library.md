# Stages

This project is language-neutral and uses the library work shape. The first card
must choose the implementation language, write `swarmforge/language.conf`,
establish the language's build, test, mutation and documentation tools, and
replace every placeholder gate before implementation.

The `spec` stage is usable immediately. Every non-spec stage runs
`scripts/stage-placeholder.sh` and fails closed until replaced.

The library lifecycle is:

```text
spec -> code -> clean -> architect -> harden -> clean-final -> verify -> docs -> deploy -> release?
```

`verify` is the library profile's public-interface lifecycle placeholder. The
profile does not imply a language, build tool or documentation checker.

Detached stages return a durable job id; end the turn without polling and
reconcile the completion wake later.

After the current spec is approved, commit the spec before implementation and
commit coherent working checkpoints as they are completed. Do not use
`--no-verify`.
