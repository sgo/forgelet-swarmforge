# Maven release boundary

GitHub Packages is the release destination. Ordinary card completion must not
write there.

## Wiring

- `swarmforge/release.conf` names the release target, normally
  `github-packages`.
- The POM's `<distributionManagement>` names the matching repository id and
  URL.
- `scripts/github-packages-token.sh` supplies the registry credential.
- The release activates `with-release-tests`; a missing or zero-check gate is a
  failure.

## Preflight

Run:

```sh
scripts/release_preflight.sh <release-version> <next-development-version>
```

Stop on anything except READY. The preflight checks the clean tree, SNAPSHOT
start version, absent tag, foreign SNAPSHOT dependencies, SCM block, release
profile, distribution repository, token helper and release plugin pin.

## Commands

Use the exact versions the card names:

```sh
mvn release:prepare \
  -DreleaseVersion=<release-version> \
  -DdevelopmentVersion=<next-development-version> \
  -Dtag=v<release-version> \
  -DpushChanges=false \
  -Darguments="-Pwith-release-tests"

mvn release:perform \
  -DlocalCheckout=true \
  -Darguments="-DskipTests"
```

The prepare command runs the release gate before tagging. Perform runs
`deploy` against the release target; skipping the repeated tests there is
intentional because prepare already gated the exact release commit.

After perform succeeds, push the branch and tag. A failed release deploy means
no next-development-version update and no successful release.
