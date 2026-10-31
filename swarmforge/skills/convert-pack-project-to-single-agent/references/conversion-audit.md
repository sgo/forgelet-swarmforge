# Conversion audit

Run these from the project root before conversion. Preserve the output with the
conversion notes.

## Repository and branches

```sh
git branch --show-current
git remote show origin
git status --porcelain
git worktree list --porcelain
git log --oneline --decorate -12
```

For every linked branch:

```sh
git cherry -v HEAD <branch>
git diff --stat HEAD...<branch>
git diff --name-only HEAD...<branch>
```

An empty path set after direct tree comparison means the branch's final content
matches the base. A branch with only patch-equivalent commits may also be safe.
Unique patches or a non-empty tree diff require review; do not delete it merely
because a clean merge succeeds.

Never convert from a dirty primary checkout. Close the project through the forge
first; a stopped process is not proof unless the forge's open-project list,
daemon/dashboard PID files and tmux sessions agree.

## Build and stages

Record:

```sh
rg '<module>|<profile>|<artifactId>|<version>' pom.xml **/pom.xml
find . -maxdepth 3 -type f \( -name 'mvnw' -o -name 'gradlew' -o -name 'package.json' \
  -o -name 'build.gradle*' -o -name 'Makefile' \) -print
```

For each proposed stage, prove its command, working directory and evidence path.
Check whether the project uses Maven reactor modules, a single module, or scripts
outside Maven. The template's module names and documentation paths are examples.

## Acceptance, QA and documentation

```sh
find acceptance qa features scripts docs -maxdepth 3 -type f -print 2>/dev/null
rg -n 'acceptance|property|mutation|jqwik|jetCheck|pitest|crap4java|dry4java' \
  pom.xml **/pom.xml scripts 2>/dev/null
```

Identify the canonical acceptance entry point, runner worker, feature source,
step handlers and evidence. Record existing `qa/*.md` procedures and their scripts.
Do not create a second runner when one already works.

## Completion and deployment

```sh
find swarmforge/hooks -maxdepth 2 -type f -print 2>/dev/null
cat swarmforge/release.conf 2>/dev/null || cat swarmforge/deploy.conf 2>/dev/null || true
find scripts -maxdepth 2 -type f -iname '*deploy*' -o -iname '*publish*'
```

A completion hook must distinguish four outcomes:

1. branch already pushed and snapshot unchanged: skip publish;
2. branch pushed and publish succeeds: report both;
3. branch pushed and publish fails: report "pushed but not published";
4. push fails: publish nothing.

Test it in a throwaway repository with a fake publisher before trusting it.

## Constitution and project rules

Inventory:

```sh
find swarmforge/constitution swarmforge/roles -maxdepth 3 -type f -print 2>/dev/null
find swarmforge -maxdepth 1 -type f -name 'local-*' -print
```

Keep privacy and contract-stability rules. Remove only rules made obsolete by the
absence of role handoffs. Preserve `local-*.prompt`, project scripts and project
configuration unless the operator explicitly retires them.

## Board and runtime

Before applying, record:

```sh
cat .swarmforge/board/tasks.tsv
find .swarmforge -maxdepth 2 -type f -print
```

After conversion, verify active cards retain names and task IDs, completed rows
remain done, pack runtime is archived, and `.swarmforge/archive/.../manifest.txt`
names the archive. Do not treat the ignored runtime archive as committed history.
