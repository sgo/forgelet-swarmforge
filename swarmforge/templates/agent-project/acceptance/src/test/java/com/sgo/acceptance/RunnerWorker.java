package com.sgo.acceptance;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runner adapter for Gherkin acceptance mutation: reads newline-delimited
 * jobs on stdin, runs the acceptance entry point against each mutated
 * feature IR, and writes newline-delimited JSON responses on stdout.
 */
public final class RunnerWorker {

    private RunnerWorker() {
    }

    public static void main(String[] args) throws Exception {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            System.out.println(runJob(line));
            System.out.flush();
        }
    }

    static String runJob(String jobLine) {
        long started = System.nanoTime();
        try {
            Map<String, Object> job = job(jobLine);
            String id = string(job.get("id"));
            String featureJson = string(job.get("feature_json"));
            if (featureJson.isBlank()) {
                throw new IllegalArgumentException("job is missing feature_json");
            }
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
            java.util.List<String> failures = AcceptanceRunner.run(Path.of(featureJson), sink);
            sink.flush();
            return jsonResponse(id,
                    failures.isEmpty() ? "test_success" : "test_failure",
                    captured.toString(StandardCharsets.UTF_8),
                    "",
                    System.nanoTime() - started);
        } catch (Exception cause) {
            return jsonResponse(idOf(jobLine),
                    "infrastructure_error",
                    String.valueOf(cause.getMessage()),
                    String.valueOf(cause),
                    System.nanoTime() - started);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(String jobLine) {
        Object parsed = MiniJson.parse(jobLine);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("job must be a JSON object");
        }
        return (Map<String, Object>) parsed;
    }

    private static String idOf(String jobLine) {
        try {
            return string(job(jobLine).get("id"));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String jsonResponse(String id, String outcome, String output,
                                      String error, long durationNanos) {
        return "{\"id\":" + JsonEscaping.quoted(id) + ","
                + "\"outcome\":" + JsonEscaping.quoted(outcome) + ","
                + "\"output\":" + JsonEscaping.quoted(output) + ","
                + "\"error\":" + JsonEscaping.quoted(error) + ","
                + "\"duration\":" + durationNanos
                + "}";
    }
}
