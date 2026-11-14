# expose-unseen-clarification-attention

# Expose unseen project clarification attention

## Operator-observed problem

`forgelet-host` needs to expose per-project clarification attention so desktop
and phone Family views can show when a project needs the operator. The shared
layer's compact `clarification_attention` currently carries `unresolved` and
`latest_change_at`, but not whether any unresolved clarification remains unseen.

The host cannot distinguish unseen from seen-but-unresolved without guessing,
owning separate clarification state, or requesting each project dashboard
separately. Those approaches violate the host card's ownership and cache
boundaries.

GitHub request `sgo/forgelet-forge#16` was filed before confirming this is a
same-forge project change. It is being closed as not planned and replaced by
this route proposal.

## Contract

Each open project's existing `clarification_attention` object must additionally
carry a fixed boolean field named `unseen`.

- `unseen` is `true` exactly when at least one clarification with lifecycle
  `open` has attention state `unseen`.
- A seen but still-open clarification leaves `unresolved` unchanged and makes
  `unseen` false when no other open clarification is unseen.
- Answering or withdrawing a clarification removes it from both facts.
- Existing `unresolved` and `latest_change_at` fields and all full-chat
  lifecycle behaviour remain unchanged.
- The object remains bounded and contains no clarification body or task text.

## Implementation shape

Derive the compact field from the same authoritative project clarification
records already used to calculate `unresolved`. Keep the lifecycle distinction:
viewing changes only unseen attention, while answering or withdrawing changes
resolution. Update the state projection, machine contract documentation and
consumer-visible fixtures together.

## Testing

Use the existing fixture project roots and in-memory dashboard state tests.
Cover:

- no clarification;
- one unseen open clarification;
- multiple unseen open clarifications;
- a seen but unresolved clarification;
- mixed seen and unseen open clarifications;
- answered and withdrawn clarifications;
- malformed attention metadata failing safely;
- restart persistence and deterministic state output.

Tests use no real service, credential, user data or active forge.

## Boundary

Work in `projects/forgelet-swarmforge`. This card extends the compact
project-slice projection and its state/documentation contract. It must not add
host-owned clarification state, scan arbitrary chat text, change the host,
render an app dot, alter Matrix clarification behaviour, or change
clarification creation/answer/withdraw semantics.

## Consumers waiting

The `forgelet-host` card
`expose-project-clarification-attention-through-host` and its later app dot are
held until this contract is delivered.

## Verification

The shared dashboard's bounded project state exposes `unseen`, `unresolved` and
`latest_change_at` consistently through unseen, seen, answered and withdrawn
lifecycle changes, with the complete layer gates passing.

The fixture covers no clarification, one and multiple unseen records, seen but
still unresolved records, mixed seen/unseen records, answered and withdrawn
records, malformed attention metadata, restart reads and deterministic state
output. The compact object contains exactly the three contract fields.

`bb test` passed 450 tests with 3,164 assertions plus all 17 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

## Operator acceptance

None.
