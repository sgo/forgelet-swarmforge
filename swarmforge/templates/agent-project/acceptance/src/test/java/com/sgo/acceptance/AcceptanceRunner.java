package com.sgo.acceptance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executable acceptance entry point: reads one feature IR file and runs every
 * scenario, printing PASS/FAIL lines. Kept separate from the generated JUnit
 * entry points so the runner adapter can drive mutated IR without JUnit.
 */
public final class AcceptanceRunner {

    private AcceptanceRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: com.sgo.acceptance.AcceptanceRunner <feature-ir.json>");
            System.exit(2);
        }
        List<String> failures = run(Path.of(args[0]), System.out);
        System.out.println();
        System.out.println(failures.size() + " scenario(s) failed");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** Runs every scenario; returns the names of the failed scenarios. */
    public static List<String> run(Path irPath, Appendable out) throws Exception {
        Ir feature = Ir.fromJson(MiniJson.parse(Files.readString(irPath)));
        AcceptanceRuntime runtime = new AcceptanceRuntime(StepHandlers.all());
        List<String> failures = new ArrayList<>();
        int run = 0;
        for (Ir.Scenario scenario : feature.scenarios()) {
            List<Map<String, String>> rows = scenario.exampleRows();
            for (int index = 0; index < rows.size(); index++) {
                run++;
                String label = scenario.rowLabel(index);
                String display = scenario.name() + label;
                try {
                    runtime.run(feature, scenario, rows.get(index));
                    out.append("PASS ").append(display).append('\n');
                } catch (Throwable failure) {
                    failures.add(display);
                    out.append("FAIL ").append(display)
                            .append(" - ").append(String.valueOf(failure.getMessage())).append('\n');
                }
            }
        }
        out.append('\n').append(String.valueOf(run)).append(" run, ")
                .append(String.valueOf(failures.size())).append(" failed\n");
        return failures;
    }
}
