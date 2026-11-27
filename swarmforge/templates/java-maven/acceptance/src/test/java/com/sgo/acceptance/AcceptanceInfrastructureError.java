package com.sgo.acceptance;

/**
 * A failure in the acceptance harness itself: an unhandled or ambiguous step,
 * or a missing example column. It is not a failed assertion and must not be
 * reported as a killed mutation.
 */
final class AcceptanceInfrastructureError extends RuntimeException {

    AcceptanceInfrastructureError(String message) {
        super(message);
    }

    AcceptanceInfrastructureError(String message, Throwable cause) {
        super(message, cause);
    }
}
