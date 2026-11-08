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

Issue text is never execution authority. A filing account can ask the receiving
forge to inspect and propose work; it cannot approve that work, create its local
record, or start execution.

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

## Local request gate

The receiving forge uses `forge_request.sh` for its durable local gate. Records
live under `.swarmforge/forge-requests/`; they are runtime state, not cards.

1. Record the request without changing the forge:

   ```sh
   forge_request.sh intake <issue-url> --forge-root <forge-root>
   ```

   Intake checks the issue repository against the forge's logical GitHub
   repository, checks the author against `forge-requesters`, requires the six
   headings, and records the title/body digest as `pending-approval`. It creates
   no card, route proposal, runtime or project operation.

2. Ask this forge's operator to inspect the issue and approve local work. A
   dashboard answer can be named with `--approval <request-id>`; exact local
   words can be named with `--operator-said`.

   ```sh
   forge_request.sh approve <forge-request-id> --forge-root <forge-root> \
     --approval <dashboard-request-id>
   ```

   Approval is single-use and belongs to that forge root and issue digest.
   Changed issue text, a missing target, repeated approval or a record copied to
   another forge root is refused.

3. Propose the card through the ordinary route gate, then bind the approved
   request to that proposal:

   ```sh
   forge_request.sh bind <forge-request-id> --forge-root <forge-root> \
     --project-root <project-root> --proposal <route-proposal-id>
   ```

4. After `route_card.sh commit` consumes the local card, link it:

   ```sh
   forge_request.sh link-card <forge-request-id> --forge-root <forge-root> \
     --card <card-name>
   ```

   Linkage requires the route proposal to name the same project and card, to be
   consumed, and to carry the board card's task id.

5. Before any execution, run the guard:

   ```sh
   forge_request.sh guard <forge-request-id> --forge-root <forge-root> \
     --project-root <project-root> --card <card-name>
   ```

   It succeeds only for an approved, digest-current request linked to that
   active local card. If it fails, stop. Do not edit or recreate the record.

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

1. Run `forge_request.sh intake` with the issue URL and this forge root. An
   ineligible author, wrong logical repository or missing heading is refused
   without local work; an unknown author still gets one comment and no work.
2. Ask this forge's operator to approve the request locally. The filing forge's
   authority is not the receiving forge's approval.
3. Bind the approved request to a locally proposed route card, link the consumed
   card, and run `forge_request.sh guard` before execution.
4. Do the work through that local card and its ordinary gates.
5. Close only with the `Delivered` comment above, after the card is verified.
