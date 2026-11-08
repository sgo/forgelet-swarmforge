# require-local-approval-for-cross-forge-requests

# Require local approval before executing a cross-forge request

GitHub issue requests currently rely on the receiving lieutenant remembering that
`forge-requesters` controls eligibility, not execution authority. A recent
Forgelet request was instead executed centrally: all three receiving forges were
composed, every open project was close/reopened, every agent session was
replaced, and the issues were closed with misleading Delivered comments. The
operator had authorized filing the requests but not executing them.

Add a durable local request-approval gate for receiving forges.

Intake:

- accept one GitHub issue URL plus the receiving forge root;
- validate the issue author/logical repository against that forge's
  `forge-requesters` file;
- fetch and hash the issue title/body/contract;
- write a local runtime record under `.swarmforge/forge-requests/` with stable
  request id, repository, issue number, URL, requester, target forge root, digest
  and status `pending-approval`;
- create no card and perform no composition, restart, deployment or project
  operation.

Approval:

- only a local operator interaction for the receiving forge can move the record
  to `approved`;
- record the operator's exact words, timestamp and approved digest;
- reject changed issue bodies, repeated use, missing target roots and approval
  under a different forge root;
- make approval single-use and link it to the locally proposed card/request.

Execution guard:

- update the shared `forge-requests-by-issue` skill and lieutenant guidance so
  an eligible issue is only a request to inspect and propose locally;
- provide a helper/contract that receiving lieutenants use to associate an
  approved request record with the local proposal and card;
- ensure routine issue handling cannot execute directly from GitHub text.
  Technical enforcement should be as strong as the existing route-card gate;
  policy text alone is insufficient.

The filing account must not be able to create the local approval marker. Do not
alter GitHub permissions, issue labels or the filing forge's authority. Do not
change queue ordering, ordinary same-forge cards or local operator workflows.

Test:

- allowed and unlisted requesters;
- issue edits after intake;
- approval reuse and mismatched issue digest;
- approval under the wrong forge root;
- an attempt to execute while pending or without a local card;
- two forge roots receiving the same issue independently;
- missing/deleted target roots;
- successful approval -> proposal -> card linkage;
- no filesystem/runtime mutation before approval.

Run all supported layer gates and the upgrade exercise. Update operator
documentation and the role prompt. Publish to `origin/forgelet`; rollout of the
new skill/scripts follows normal forge composition.
