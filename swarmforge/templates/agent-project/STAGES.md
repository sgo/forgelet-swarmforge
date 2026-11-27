# Stages

This project is language-neutral. The first card must choose the project
language, write `swarmforge/language.conf`, establish that language's build,
test, mutation and documentation tools, and replace the placeholder gates in
`stages.edn` before product implementation.

The `spec` stage is usable immediately. Every non-spec stage runs
`scripts/stage-placeholder.sh` and fails closed until replaced. A placeholder
must never be treated as passing evidence.

The standard lifecycle is:

```text
spec -> code -> clean -> architect -> harden -> clean-final -> qa -> e2e -> docs -> deploy -> release?
```

The library profile uses `verify` instead of `qa` and `e2e`; the language is
still not implied by the profile.

An operator or automatic spec approval moves the active card to `code`. That is
visibility only and does not run the code gate.

Detached stages return a durable job id. End the turn without polling. The
completion wake enables `stage status --json` or `stage check <name>` to
validate the exit, evidence and source stamps once.

Every new or next-touched task note carries `## Verification` and
`## Operator acceptance`. Pending acceptance does not replace a required gate.

After the current spec is approved, commit the spec before implementation and
commit coherent working checkpoints as they are completed. Do not use
`--no-verify`.

The first card may retain this profile's work shape while changing all language
specific commands, evidence, tooling and documentation contracts.
