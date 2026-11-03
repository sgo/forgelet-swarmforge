# Agent instructions

This repository is the shared Forgelet layer, owned by the managed
`forgelet-swarmforge` project in `forgelet-forge`. Read `mission.md`,
`STAGES.md` and this project's `stages.edn` before changing the layer.

Work on the `forgelet` branch. Do not target `master`. Keep authoring and tests
inside this checkout; do not edit the active forge root's shared scripts
directly. Never stop, restart or tear down `/Users/sgo/forgelet-forge` as part
of layer work. Release to the active forge only by composing this project after
changes are committed and pushed to `forgelet`.

Do not test the text of prompts with an automated unit or acceptance test.
That includes constitution articles, role prompts, Tool Startup, and generated
instruction files. Prompt wording is not production behavior to pin with
`str/includes?`, Gherkin, or any other automated check.
