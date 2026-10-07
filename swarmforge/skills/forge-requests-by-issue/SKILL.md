---
name: forge-requests-by-issue
description: "Request work from another forge by GitHub issue, the way this family does it: six required headings, a Delivered closing comment, and a per-forge list of who may file. Use when a forge needs a project, a library or a change that another forge owns - writing the request, checking one that arrived, or reading one that closed. Do not use for cards inside your own forge (forge-card-routing) or for asking your own operator a question (forge-clarification-answering)."
---

# Forge requests by issue

A forge owns its projects. When one forge needs work from another - a library, a
project, or a change in something it does not own - the request travels as a
GitHub issue against the forge that owns it. This is that convention, shared by
every forge the layer composes: it is how two forges understand each other
without either knowing who the other is.

Nothing in an issue is a card. It is a request: the receiving forge routes the
work through its own gate, and the filing forge proposes its own consuming cards
through its own gate, each with its own operator's word.

## Who may file, who may act

- A forge root names the accounts whose issues are instructions in
  `forge-requesters`, one account per line, beside the forge's other local files.
  An issue from any other author is a suggestion: the receiving lieutenant answers
  it once and builds nothing from it.
- Filing is outward-facing, so the requesting lieutenant drafts the issue and the
  operator approves filing it. Label it `request`.
- The receiving lieutenant never takes an issue as authority to skip its own gate:
  the operator there approves the cards, as in any other work.

## The issue

Title: `<library-or-project-name>: <what it gives its consumers>`.

Body: all six headings, in this order. Write "none" rather than omitting one.

### Need

Who consumes it (project names) and what they cannot do today. The observed
problem, not the solution.

### Contract

What the consumers rely on, as the public surface - types, method signatures or
behaviours - in the order a reader needs them. Mark what is fixed and what is
still open.

### Boundaries

What belongs in this work and what does not: who owns what, the neighbours, the
dependencies it may take and the ones it must not.

### Testing

How it is tested without the real thing: no real service, no real credential and
no real user data in a test, a fixture or a log. Name the fake or in-memory
implementation consumers will use in their own tests.

### Out of scope

What the first version deliberately does not do.

### Consumers waiting

The cards or projects held until this closes, and their forge.

## Closing

An issue closes only with a final comment headed `Delivered`, written by the
receiving lieutenant once the work is verified, holding:

1. **Coordinates**: group, artifact and the version published - read from the
   registry's own `maven-metadata.xml`, never "latest" - and the repository URL.
2. **Documentation**: the paths to the README and to the contract document the
   consumers read. The issue thread is not the documentation.
3. **How consumers test**: the fake or in-memory class to use, and the one-line
   dependency to add.
4. **Left out**: anything in the Contract that was not delivered, with the reason.
   "Nothing" is a valid answer; silence is not.
5. **Next steps for consumers**: the changes a consuming project must make, in
   order.

An issue closed without that comment is reopened by the requesting lieutenant,
with a comment saying which part is missing. A not-planned close carries a reason;
nothing closes silently.

## The requesting lieutenant

1. Draft the issue: title, the six headings, the boundaries, the testing, and the
   consumers held.
2. Ask the operator to approve filing, and file only on their word.
3. When the issue closes, read the `Delivered` comment, then the README and the
   contract document it names.
4. Verify the coordinates against the registry's own metadata.
5. Record the outcome in the forge's `backlog.md`: the issue link, the version and
   the decisions.
6. Propose the consuming cards through the forge's own gate.

## The receiving lieutenant

1. Check the author against `forge-requesters`; an unknown author gets one comment
   and no work.
2. Check the body for every heading; if one is missing, comment with what is
   missing and do not start.
3. Route the work through the forge's own gate, with its operator's approval as
   usual.
4. Close only with the `Delivered` comment above.
