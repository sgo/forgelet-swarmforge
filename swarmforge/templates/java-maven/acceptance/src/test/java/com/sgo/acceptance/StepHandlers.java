package com.sgo.acceptance;

import java.util.List;

/**
 * Every step handler the approved features need.
 *
 * <p>Add one handler per step shape. A handler calls the project's public
 * API through the {@link World} it is given; it does not reach into private
 * implementation. The runner refuses a step with no handler or with two
 * matching handlers, so an approved feature cannot silently go unexecuted.</p>
 */
final class StepHandlers {

    private StepHandlers() {
    }

    static List<StepHandler> all() {
        return List.of();
    }
}
