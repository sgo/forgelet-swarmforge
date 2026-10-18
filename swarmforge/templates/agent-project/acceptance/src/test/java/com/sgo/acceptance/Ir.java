package com.sgo.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parser-produced feature representation handed to the acceptance
 * runtime. Mirrors the JSON IR emitted by {@code gherkin-parser}.
 */
record Ir(String name, List<Step> background, List<Scenario> scenarios) {

    public Ir {
        background = List.copyOf(background);
        scenarios = List.copyOf(scenarios);
    }

    public record Step(String keyword, String text) {
    }

    public record Scenario(String name, List<Step> steps, List<Map<String, String>> examples) {

        public Scenario {
            steps = List.copyOf(steps);
            examples = List.copyOf(examples);
        }

        /** One row per example; a scenario without examples still runs once. */
        public List<Map<String, String>> exampleRows() {
            return examples.isEmpty() ? List.of(Map.of()) : examples;
        }

        /** Label for the row at the given index; scenarios without examples run once unlabeled. */
        public String rowLabel(int index) {
            return examples.isEmpty() ? "" : "/example_" + (index + 1);
        }
    }

    /** Converts a parsed {@link MiniJson} object into this representation. */
    @SuppressWarnings("unchecked")
    public static Ir fromJson(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("feature IR must be a JSON object");
        }
        Map<String, Object> feature = (Map<String, Object>) raw;
        return new Ir(
                string(feature.get("name")),
                steps(feature.get("background")),
                scenarios(feature.get("scenarios")));
    }

    @SuppressWarnings("unchecked")
    private static List<Step> steps(Object raw) {
        List<Step> steps = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> step = (Map<String, Object>) item;
                steps.add(new Step(string(step.get("keyword")), string(step.get("text"))));
            }
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static List<Scenario> scenarios(Object raw) {
        List<Scenario> scenarios = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> scenario = (Map<String, Object>) item;
                List<Map<String, String>> examples = new ArrayList<>();
                if (scenario.get("examples") instanceof List<?> exampleList) {
                    for (Object example : exampleList) {
                        Map<String, Object> row = (Map<String, Object>) example;
                        Map<String, String> resolved = new LinkedHashMap<>();
                        row.forEach((key, value) -> resolved.put(key, string(value)));
                        examples.add(resolved);
                    }
                }
                scenarios.add(new Scenario(
                        string(scenario.get("name")),
                        steps(scenario.get("steps")),
                        examples));
            }
        }
        return scenarios;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
