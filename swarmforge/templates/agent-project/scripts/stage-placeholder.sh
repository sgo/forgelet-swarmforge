#!/bin/sh
set -eu

stage="${1:-stage}"
cat >&2 <<EOF
Stage '$stage' is a language-neutral placeholder and has not run.
The first card must:
  1. choose the project language and write swarmforge/language.conf;
  2. establish that language's build, test, mutation and documentation tools;
  3. replace every placeholder stage command in stages.edn;
  4. replace this helper or remove it when no longer used.
EOF
exit 1
