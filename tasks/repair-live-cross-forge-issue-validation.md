# repair-live-cross-forge-issue-validation

# Repair live cross-forge issue validation

## Verification failure

Completed card `validate-cross-forge-issue-drafts-before-filing` added a shared
renderer and validator. Its body parser and receiver contract are correct, but
the live fetched-issue path fails with installed `gh 2.102.0`:

```text
Could not fetch issue ...: Unknown JSON field: "repository"
```

`gh issue view` exposes `number`, `title`, `body`, `url` and `author`, but no
`repository` field in this installed version. The helper currently requests that
unsupported field before validating the issue body.

## Desired behavior

- Derive owner/repository identity from the canonical GitHub issue URL rather
  than requesting an unsupported JSON field.
- Request only GitHub CLI fields available in the supported installed version.
- Validate that the returned issue number and canonical URL agree with the
  requested URL.
- Preserve the existing title, body, digest and authority checks.
- Apply the correction to both post-filing `validate-issue` and receiving
  `intake`.
- Keep the same six-heading parser and do not change issue or approval policy.

## Testing

- A fake `gh` shaped like the installed JSON field set accepts a valid issue.
- The fake fails when the URL/returned number disagree.
- Title mismatch and malformed body remain refused.
- Receiving intake accepts the same corrected issue.
- A regression invocation validates the corrected
  `https://github.com/sgo/innersource-forge/issues/11` live.
- No issue is filed, approved, composed or executed by validation.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not weaken requester trust, local
operator approval, request digest binding, or the six-heading grammar.

## Verification

`validate-issue` and `intake` succeed against a valid issue with the installed
GitHub CLI and continue to fail closed on changed or malformed issue data.

## Operator acceptance

None.
