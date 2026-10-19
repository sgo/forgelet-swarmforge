package com.sgo.acceptance;

/**
 * Escapes text for embedding inside JSON output and Java string literals.
 * Shared by the runner worker's JSON responses and the generated JUnit
 * entry points so the two escapers cannot drift apart.
 */
final class JsonEscaping {

    private JsonEscaping() {
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The escaped text wrapped in double quotes for a string literal. */
    static String quoted(String value) {
        return "\"" + escape(value) + "\"";
    }
}
