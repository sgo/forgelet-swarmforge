package com.sgo.acceptance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects one shape of step text to project behavior. Patterns match the
 * full step text, so repeated step shapes share one handler with regex
 * captures for the values that vary.
 */
record StepHandler(Pattern pattern, Body body) {

    public static StepHandler step(String regex, Body body) {
        return new StepHandler(Pattern.compile(regex), body);
    }

    boolean matches(String text) {
        return pattern.matcher(text).matches();
    }

    void execute(World world, String text) throws Exception {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalStateException("step text does not match: " + text);
        }
        body.execute(world, new Arguments(matcher));
    }

    @FunctionalInterface
    public interface Body {
        void execute(World world, Arguments arguments) throws Exception;
    }

    /** Captured regex groups of the matched step text. */
    public static final class Arguments {

        private final Matcher matcher;

        private Arguments(Matcher matcher) {
            this.matcher = matcher;
        }

        public String text(int group) {
            String value = matcher.group(group);
            if (value == null) {
                throw new AssertionError("step did not capture group " + group);
            }
            return value;
        }

        /** The captured group, or null when the group did not participate. */
        public String textOrNull(int group) {
            return matcher.group(group);
        }
    }
}
