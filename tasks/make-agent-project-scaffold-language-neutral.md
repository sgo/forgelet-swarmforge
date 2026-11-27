# make-agent-project-scaffold-language-neutral

Make the default `get-agent-project` scaffold language-neutral while preserving
the current Java/Maven scaffold as an explicit starter template.

The app's `application` and `library` choices describe the project's execution
profile. They do not choose a language. Today, however, the default template
contains `swarmforge/language.conf` set to `Java`, a Maven POM and settings,
Maven stage commands, Java acceptance helpers and Java-specific guidance. That
silently decides a fact the family rules say belongs only to the project.

## Desired outcome

- The default `get-agent-project` composition creates a generic single-agent
  project with mission, selected agent, one-agent role, Git, board/messaging
  integration, feature and task locations, shared runtime and neutral
  instructions.
- Generic creation writes no `swarmforge/language.conf` and creates no POM,
  Gradle files, Java acceptance source, snapshot installer or other
  language-specific build artifact.
- The generic stage configuration is fail-closed. The `spec` stage remains
  usable, while every build, test, mutation, documentation, deploy and optional
  release placeholder exits nonzero with a bounded instruction to select a
  language and replace the placeholder gates during the first card. A
  placeholder must never record a successful gate or missing evidence as a
  pass.
- Generic `AGENTS.md` and `STAGES.md` explain that the first card must choose the
  project language, write `swarmforge/language.conf`, establish the language's
  build/test/mutation/documentation gates and replace the placeholders before
  implementation.
- Both `application`/`standard` and `library` profiles remain universal
  work-shape choices. Their generic scaffolds differ only in the QA versus
  verify lifecycle placeholder, not in language.
- Preserve the current complete Java/Maven template as an explicit starter,
  selected through an allow-listed `--template java-maven` option. The default
  when the option is omitted must be `generic`. The Host and app continue to
  omit template selection in this card, so app creation becomes generic.
- Selecting the explicit Java/Maven starter may seed the project's own
  `language.conf` with `Java` because that is the operator-visible template
  choice. Generic creation must leave the file absent.

## Upgrade and cache compatibility

- `get-agent-project --upgrade` must continue upgrading existing Java/Maven
  projects without removing source or gates and without treating a generic
  project as Java.
- A generic project that later adds Maven files through its first card may use
  the existing Maven-aware upgrades for build-specific additions, but no
  upgrade may infer or write its initial language on its own.
- The versioned installed composer cache must contain both templates and the
  placeholder helper. Cache completeness and matching must cover both
  template roots. Reinstall the cache from a disposable fixture and prove both
  default generic creation and explicit Java/Maven creation.

## Verification

- Fixture tests compose generic standard and library projects and prove the
  common shell exists, `language.conf` and language-specific build files do
  not, and the agent/mission/profile/Git/runtime contracts are intact.
- Attempt representative non-spec gates before setup and prove each fails with
  actionable guidance and records no success.
- A disposable Java first-card setup writes `Java` and Maven infrastructure,
  replaces the placeholders and passes a representative code gate.
- At least one non-Java disposable setup proves the scaffold does not secretly
  assume Maven or Java.
- The explicit `--template java-maven` path reproduces the current complete
  Java/Maven standard and library scaffolds.
- Existing Java project upgrade tests, generic upgrade tests, installed-cache
  tests, architecture/clean/hardening/documentation gates and the layer's full
  suite pass.

## Boundaries

- Do not change the app or Host template contract in this card. A later,
  separately approved card may expose optional template selection.
- Do not modify or repair `forgelet-core` here. Its next card will explicitly
  choose Java/Maven and establish that project's infrastructure.
- Do not run destructive or lifecycle commands against the active forge. All
  composition, upgrade and cache tests use disposable roots and fixture homes.

## Operator acceptance

- Owner: operator | environment: Forgelet desktop and mobile app | checks:
  create a generic project from the app, confirm no language or build files are
  assumed, then confirm an explicitly Java/Maven scaffolded project remains
  selectable through the supported internal contract | status: pending
