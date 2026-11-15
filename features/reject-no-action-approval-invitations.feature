Feature: Reject no-action approval invitations

  Scenario: Require an action for an explicit approval invitation
    Given an answer explicitly invites approval for one pending local proposal
    When completion has no matching approval action
    Then the request remains pending
    And the diagnostic names the proposal and repair flag

  Scenario: Reject the no-action override on an invitation
    Given an approval invitation names a pending local proposal
    When the answer supplies --without-route-card-action
    Then completion is refused
    And the answer must be rewritten as discussion-only text

  Scenario: Preserve discussion-only proposal references
    Given an answer names a pending proposal without inviting approval
    When the answer supplies --without-route-card-action
    Then completion succeeds without an action

  Scenario: Ignore non-live proposal invitations
    Given invitation wording names consumed, malformed or foreign proposals
    When the local answer completes
    Then no approval candidate or action is inferred

  Scenario: Reject ambiguous multiple invitations
    Given one answer invites approval for multiple live local proposals
    When one or no approval action is supplied
    Then completion is refused
    When the answer is rewritten to invite exactly one proposal
    Then its matching action can complete the request
