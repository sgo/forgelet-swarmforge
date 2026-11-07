# manage-proposals-machine-readably

# Expose proposal management through the routing tool

The app needs to list pending route proposals, inspect one safely and approve it
through the existing gate. Those records currently exist only as local EDN under
`<forge>/.swarmforge/route-proposals`, and `route_card.sh` only offers human CLI
output.

Extend `swarmforge/scripts/route_card.bb` with machine-readable operations:

- list unconsumed proposals for one project or the forge, including id, card
  name, target project, original lane, creation time and status;
- inspect one proposal with its full note and current consumed/task state;
- approve one pending proposal through the same single-use operator gate and
  card creation path used by `route_card.sh commit`.

Use JSON on stdout only for machine operations and keep existing human commands
compatible. Validate proposal ids and resolved project roots. Never return
absolute host paths, tokens or unrelated files. Approval output must include the
actual assigned lane (`queued` or `spec`) and created task id.

Do not read or write proposals directly from Forgelet Host in this card; its
adapter belongs to the next host card. Do not change proposal approval wording,
queue ordering or card creation semantics.

Test list/detail filtering, full note fidelity, consumed/superseded handling,
machine-output escaping, malformed EDN, missing projects, one-use approval,
duplicate approval, and actual queued/spec reporting. Keep unrelated shared
script changes untouched. Run the layer gates, commit and push `forgelet`.
