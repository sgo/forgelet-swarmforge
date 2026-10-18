package com.sgo.acceptance;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the steps of one scenario against the project step handlers. The
 * executable acceptance runner delegates here, and the generated JUnit
 * entry points reach it through {@link AcceptanceEntryPoints}; the runtime
 * itself stays free of JUnit types so it can also run mutated IR outside
 * the JUnit engine.
 */
final class AcceptanceRuntime {

    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>]+)>");

    private final List<StepHandler> handlers;

    public AcceptanceRuntime(List<StepHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /** Runs one scenario against a fresh {@link World}. */
    public void run(Ir feature, Ir.Scenario scenario, Map<String, String> example) throws Exception {
        World world = new World();
        for (Ir.Step step : feature.background()) {
            executeStep(world, step, example);
        }
        for (Ir.Step step : scenario.steps()) {
            executeStep(world, step, example);
        }
    }

    private void executeStep(World world, Ir.Step step, Map<String, String> example) throws Exception {
        String text = substitute(step.text(), example);
        try {
            handlerFor(text).execute(world, text);
        } catch (AssertionError cause) {
            throw new AssertionError(
                    step.keyword() + " " + step.text() + "\n  " + cause.getMessage(),
                    cause);
        }
    }

    private StepHandler handlerFor(String text) {
        List<StepHandler> matching = handlers.stream()
                .filter(handler -> handler.matches(text))
                .toList();
        if (matching.isEmpty()) {
            throw new AssertionError("no step handler for: " + text);
        }
        if (matching.size() > 1) {
            throw new AssertionError("ambiguous step handlers for: " + text);
        }
        return matching.get(0);
    }

    private static String substitute(String text, Map<String, String> example) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            result.append(text, end, matcher.start());
            String column = matcher.group(1);
            String value = example.get(column);
            if (value == null) {
                throw new AssertionError("step wants <" + column
                        + "> but the example row only has " + example.keySet());
            }
            result.append(value);
            end = matcher.end();
        }
        result.append(text, end, text.length());
        return result.toString();
    }
}
