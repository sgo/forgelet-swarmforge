Feature: Repair live cross-forge issue validation

  Scenario: Fetch with the installed GitHub CLI field set
    Given a fake GitHub CLI supports author, body, number, title and URL
    When a valid issue is fetched
    Then owner and repository identity comes from the canonical issue URL
    And validation succeeds without requesting an unsupported repository field

  Scenario: Reject mismatched fetched identity
    Given the fetched issue number or URL differs from the requested URL
    When fetched-issue validation runs
    Then the mismatch is refused

  Scenario: Preserve content and receiver authority checks
    Given a fetched issue has a mismatched title or malformed six-section body
    When fetched-issue validation or receiving intake runs
    Then the request is refused
    And requester trust, digest binding and local operator approval remain required

  Scenario: Validate the live regression issue
    Given the installed GitHub CLI supports the corrected field set
    When the helper validates sgo/innersource-forge#11
    Then the corrected issue validates without filing or approving anything
