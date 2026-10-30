# Proven Java testing stack

Use the project's existing choices when they work. The settings below are proven
defaults for this family, not permission to overwrite a stronger project-specific
contract.

## Property tests

Use `org.jetbrains:jetCheck:0.3.0` at test scope with JUnit 5. jetCheck shrinks a
failing input and can print a serialized value for replay. The family uses it in
saibill-neutral, saibill-persistence-spi, saibillx-persistence, saibill-server and
saibill-api.

- Generate structured values by part: lists, strings, amounts and dates each
  retain their own generator and shrink independently.
- Tag property tests `property`.
- Put them behind a `with-property-tests` Maven profile.
- Provide a deliberate-failure switch for proving the runner executes tests.
- Fail the gate on zero checks, not merely on failed checks.

## Mutation tests

Use:

- `org.pitest:pitest-maven:1.20.7`
- `org.pitest:pitest-junit5-plugin:1.2.3`
- PIT history enabled
- target classes supplied by project parameter
- `threads=1`
- `mutationCoverage` bound to the `verify` phase in
  `with-mutation-tests`
- Surefire skipped during that profile while PIT's own `skipTests` is false

A profile that merely makes PIT available without binding a goal runs no mutation
tests. Fail the harden stage when:

- the XML report is absent;
- the mutation count is zero;
- a mutant survives on a changed line without an approved equivalent-mutant
  reason.

For projects with many classes, run one class per invocation rather than one
unbounded reactor run.

Gherkin mutation is separate. Run the project's acceptance mutation script at
the hard level on copies under `tmp/`, and fail on survivors or infrastructure
errors. It must leave approved feature files byte-for-byte unchanged.

## Clean and architecture checks

The proven family defaults are:

```sh
crap4java --test-command "mvn -q -Pwith-unit-tests test" .
dry4java --format edn src/main/java
```

Require a CRAP ceiling of 6.0. In a commit-per-card workflow, compare CRAP, DRY,
PIT and coverage against the card's base commit, not the uncommitted index; an
uncommitted-file filter sees nothing after the checkpoint commit.

## Release checks

- Source the GitHub Packages token helper; executing it in a subshell loses the
  exported token.
- Resolve release properties from the POM rather than scanning one narrow block.
- Fail when any SNAPSHOT dependency remains for a release.
- Acceptance should accept the pinned released version or a later floating
  development snapshot as "at least the released version".
