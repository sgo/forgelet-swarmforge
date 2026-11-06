# publish-project-snapshots-to-host-repository

# Publish project snapshots to the host repository

## Operator-observed need

Each single-agent Maven project's deploy gate currently runs `mvn install`.
Agents must keep resolving locally through `~/.m2`, while the operator's other
machines need immutable, Maven-recognisable snapshot builds from the dedicated
repository.

Project repositories must not record the devbox repository's ID, URL, path or
existence.

## Desired behaviour

- The generic shared `scripts/install-snapshot.sh` still refuses non-SNAPSHOT
  project versions.
- The deploy invocation builds and tests through `with-release-tests`.
- It runs Maven's `deploy` lifecycle so the normal local `install` phase still
  updates `~/.m2` before remote snapshot publication.
- It supplies:

  ```text
  -DaltSnapshotDeploymentRepository=$SWARMFORGE_MAVEN_SNAPSHOT_DEPLOY_REPOSITORY
  ```

- That host-supplied value uses Maven's
  `<repository-id>::default::<repository-url>` form.
- A missing or malformed value fails the deploy gate with a precise message
  rather than silently deploying only locally.
- `altSnapshotDeploymentRepository` leaves deliberate release deployment and
  GitHub Packages wiring unchanged.
- No project POM, settings file, stage file, task, documentation or source file
  contains the devbox URL or path.

## Acceptance

- Fixture Maven projects prove deploy publishes timestamped files to an
  alternate file repository.
- The same projects prove the artifact is also installed in `~/.m2`.
- Missing/malformed deployment configuration refuses before deployment.
- Release preflight and release repository wiring remain unchanged.
- A source scan proves no concrete devbox snapshot repository value appears in
  a project checkout.
- Layer `code` and `clean` stages pass on the committed `forgelet` tree.
