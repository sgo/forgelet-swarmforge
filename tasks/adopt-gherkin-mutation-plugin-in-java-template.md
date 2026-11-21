# adopt-gherkin-mutation-plugin-in-java-template

# Adopt the Gherkin mutation Maven plugin in the Java template

## Dependency

Follows the verified `forgelet-mcp` adoption of
`sgo.innersource:gherkin-mutation-maven-adapter`.

## Operator-observed need

New single-agent Java projects still receive the legacy
`scripts/run-acceptance-mutation.sh` and require a project-local
`gherkin-mutator`. Innersource now provides the supported Maven plugin,
including structured reports, cache behavior, source preservation and explicit
goals.

## Desired behaviour

- Update the shared standard and library Java project templates to configure
  `gherkin-mutation-maven-adapter` explicitly.
- Generate the harden stage as a separate approved `gherkin-mutation:check`
  invocation after PIT, not bound to ordinary compile/test/package phases.
- Configure feature roots, the generated acceptance runner command, hard level,
  bounded workers, project identity and initial dependency fingerprints.
- Record `target/gherkin-mutation/reports/mutation-report.json` as gate evidence.
- Remove the legacy shell mutator from generated projects, stage inputs, README,
  AGENTS/STAGES guidance, the managed
  `swarmforge/constitution/articles/engineering.prompt` Java rules and
  tool-install requirements.
- Preserve project-specific acceptance runners; adopters may override the
  configured command but cannot replace the plugin with a global mutator.
- Preserve support for existing projects until they migrate through their own
  route-card-gated cards.

## Testing

- Template composition tests assert the plugin, explicit goal, structured
  evidence and absence of the legacy script/global mutator dependency for both
  standard and library profiles.
- Newly composed Java projects must contain no positive instruction requiring
  `gherkin-mutator` or the deleted script in either agent guidance or the
  managed engineering prompt.
- New-project fixtures run the generated harden gate without `gherkin-mutator`
  on `PATH` and prove feature bytes unchanged.
- Tests preserve existing project-owned runner overrides and AGENTS additions.
- Upgrade tests distinguish new/opted-in projects from existing projects that
  have not yet migrated.
- Full layer tests, dashboard projections and generated project acceptance pass.

## E2E

Create disposable standard and library projects from the updated template, run
their harden gates, inspect the structured reports, mutate a deliberate
survivor and verify failure, then confirm a clean rerun and source preservation.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not edit existing project POMs in this
card, bind mutation to normal lifecycle phases, hand-edit APS manifests, or
remove the legacy path from projects before their own migration cards pass.

## Verification

The standard and library template fixtures run the explicit Maven adapter gate
without the global mutator, deliberate failures remain retryable, structured
reports and unchanged feature bytes are proven, and unmigrated projects retain
their existing mutation path until opted in by their own card.

## Operator acceptance

None.
