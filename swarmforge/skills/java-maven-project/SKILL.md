---
name: java-maven-project
description: "Set up or change a Java/Maven project in this forge, including its build profiles, Maven repositories, local snapshot deploy gate, artifacts consumed by neighbouring projects, and release publishing. Use for single-agent Maven build, stage, dependency or release wiring; do not use for non-Maven projects or ordinary Java source edits that leave the build unchanged."
---

# Java Maven project

The project's POM and Maven settings are its build contract. The shared
forge engineering rules are in
`swarmforge/constitution/articles/engineering.prompt`; read the Java sections
before changing profiles or gates.

## Repository boundary

For a single-agent project on the devbox:

- Maven resolves and installs local artifacts in the operator's standard
  `~/.m2`.
- Do not set `-Dmaven.repo.local=.m2/repository` or another project-local local
  repository in `.mvn/maven.config`.
- Keep `.mvn/settings.xml` for registry credentials and repository-specific
  configuration. A settings file is not a local artifact repository.
- Other projects on the same devbox resolve a producer's current SNAPSHOT after
  its deploy gate. A card may depend on the producer's installed SNAPSHOT; it
  must not expect another operator's devbox or GitHub Packages to hold it.

The shared local repository is not a multi-writer transaction boundary. Avoid
concurrent builds publishing the same group/artifact/version. Unique producer
coordinates and one writer per project are the supported arrangement.

Existing multi-agent pack projects may retain worktree-isolated repositories
until they are retired or converted.

## Gate contract

Use the project's Maven wrapper when it has one; otherwise use `mvn`. Keep the
project's real Java version and profiles.

The ordinary single-agent pipeline remains:

```text
code -> clean -> architect -> harden -> clean-final -> qa/verify -> docs -> deploy -> release?
```

- `code`, `clean`, `architect`, `harden`, `clean-final` and `qa`/`verify` judge
  the source. A gate that exits zero after running zero tests or mutations is a
  failure.
- `deploy` installs the current `-SNAPSHOT` for other local projects:

  ```sh
  scripts/install-snapshot.sh
  ```

  The script requires a SNAPSHOT and runs the project's
  `with-release-tests` profile through `mvn install`. Card completion does not
  publish to GitHub Packages.
- `release` is deliberate and optional per card. Read
  [references/release.md](references/release.md) before changing release
  wiring.

## Quality profiles

Keep these Maven profiles as the stable names across the family:

- `with-unit-tests`: unit tests.
- `with-property-tests`: property tests; fail on zero checks.
- `with-mutation-tests`: PIT plus Gherkin acceptance mutation; fail when PIT
  reports zero mutations or a non-equivalent survivor is uncovered.
- `with-release-tests`: the complete gate a release prepare activates.

Use one Maven invocation at a time. Do not run CRAP, DRY, coverage, mutation
and acceptance generation concurrently.

For CRAP and DRY during a card, compare against the card's base commit rather
than filtering only uncommitted files. A committed card otherwise looks empty.

## When changing the build

1. State the public behaviour the build change serves.
2. Inspect the root and module POMs before adding a profile or dependency.
3. Prove the local install path from a clean checkout:

   ```sh
   mvn -q -Pwith-unit-tests verify
   scripts/install-snapshot.sh
   ```

4. Verify a neighbouring project can resolve the installed SNAPSHOT from
   `~/.m2`, using only the coordinate it declares.
5. Verify release preflight still sees the release repository and token helper,
   but do not run a release unless the card requests one.

Never use `versions:set` to cut a release. Use the card's exact
`release:prepare` and `release:perform` versions.
