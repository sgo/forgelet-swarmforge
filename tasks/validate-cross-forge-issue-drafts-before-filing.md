# validate-cross-forge-issue-drafts-before-filing

# Validate cross-forge issue drafts before filing

## Operator-observed problem

Forgelet filed `sgo/innersource-forge#11` with six correct headings at Markdown
level two. Innersource intake correctly refused it because the shared request
contract requires level three. The format existed only as prose, so a manual
transcription changed heading depth and the receiver found it.

## Desired behavior

- Provide one canonical request template with exactly the six required `###`
  headings in order.
- Add a pre-filing operation that accepts the six structured sections and
  renders the issue body.
- Refuse wrong heading depth, missing, duplicate, empty or out-of-order
  sections before outward-facing filing.
- Optionally fetch and validate an already-created issue body before the
  requesting Lieutenant announces it to the receiving forge.
- Reuse the same heading contract as receiving intake; do not maintain a second
  format definition.
- Keep issue text as request-only, without execution authority.

## Testing

- Valid structured input renders exactly one of each required `###` heading in
  order with body content preserved.
- Level-two headings, missing/duplicate/out-of-order sections and empty sections
  are refused with bounded diagnostics.
- Fenced examples and ordinary Markdown inside a section do not count as
  headings.
- The receiving intake validator accepts the rendered body.
- An already-created GitHub issue round-trip validates the fetched title/body
  and detects later heading changes.

## E2E

Render a disposable six-section request, create it in a test repository fixture,
fetch it through the same interface used after filing, and prove the receiving
validator accepts it. Mutate one heading's depth and prove both pre-filing and
post-filing checks refuse it.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not file an issue without operator
approval, change the six-section meaning, infer authority from issue authorship,
or maintain a separate app/host copy of the request format.

## Verification

Cross-forge issue bodies are generated or validated against the receiver's exact
heading contract before filing, and the same check can validate a fetched issue.

## Operator acceptance

None.
