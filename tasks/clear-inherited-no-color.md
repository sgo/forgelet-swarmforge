# clear-inherited-no-color

# Clear inherited NO_COLOR at the agent-session boundary

Kitty theme files are loading correctly, but syntax highlighting and coloured
diff output disappear in many agent panes because their persistent processes
inherit `NO_COLOR=1`. Live comparison confirms the correlation: Saibill's
working older project agents have it unset, Forgelet's lieutenant has it unset,
and the affected Forgelet, Innersource and Ledgerhall agent processes have it
set.

At the persistent single-agent session launch boundary in
`swarmforge/scripts/agent-runner.bb`, explicitly remove inherited `NO_COLOR`
before starting the selected agent. Do not edit or reinterpret Kitty theme
files, change terminal branding, remove `TERM`, overwrite `COLORTERM`, or rely
on a user's shell profile. The fix must work for every supported agent and for
sessions started through Forgelet Host, the owning forge, or direct recovery.

An existing tmux server and its persistent `swarmforge-agent` session retain
their original environment. This card changes the launch path; rollout requires
replacing affected sessions once after the layer is composed. Do not claim that
an in-place source update themes an existing process.

Add a fixture regression that starts the agent session from a parent with
`NO_COLOR=1` and proves the launched child does not receive `NO_COLOR`.
Also cover the selected-agent variants without requiring a real model/service,
and prove normal variables such as `TERM`, `COLORTERM` and the project runtime
environment survive. Preserve unrelated uncommitted shared-script refresh
changes in this checkout.

Run the layer unit, property, acceptance, dashboard, architecture, CRAP/DRY,
mutation, documentation and upgrade-exercise gates. Compose the committed layer
into Forgelet, refresh every open single-agent project through the normal forge
lifecycle, restart its agent session, prove `NO_COLOR` is absent from the live
child environment, and ask the operator to verify syntax and diff colours in
Kitty. Then update and close the three receiving-forge requests once each forge
has applied the same compose/refresh/restart sequence.
