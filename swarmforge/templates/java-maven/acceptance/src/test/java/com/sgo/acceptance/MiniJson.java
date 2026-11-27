package com.sgo.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the acceptance pipeline's parser IR. */
final class MiniJson {

    private MiniJson() {
    }

    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + index);
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("unexpected end of JSON");
            }
            return text.charAt(index);
        }

        char take() {
            char current = peek();
            index++;
            return current;
        }

        void expect(char expected) {
            char current = take();
            if (current != expected) {
                throw error("expected '" + expected + "' but found '" + current + "'");
            }
        }

        Object parseValue() {
            skipWhitespace();
            return switch (peek()) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char separator = take();
                if (separator == '}') {
                    return result;
                }
                if (separator != ',') {
                    throw error("expected ',' or '}' in object");
                }
            }
        }

        List<Object> parseArray() {
            expect('[');
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char separator = take();
                if (separator == ']') {
                    return result;
                }
                if (separator != ',') {
                    throw error("expected ',' or ']' in array");
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char current = take();
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    value.append(escapedCharacter());
                } else {
                    value.append(current);
                }
            }
            throw error("unterminated string");
        }

        private char escapedCharacter() {
            char escaped = take();
            return switch (escaped) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicodeEscape();
                default -> throw error("invalid escape '\\" + escaped + "'");
            };
        }

        char unicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("truncated unicode escape");
            }
            int code = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(text.charAt(index + offset), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                code = (code << 4) | digit;
            }
            index += 4;
            return (char) code;
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, index)) {
                throw error("expected " + word);
            }
            index += word.length();
            return value;
        }

        Object parseNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            consumeDigits();
            if (!atEnd() && peek() == '.') {
                index++;
                consumeDigits();
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                index++;
                if (!atEnd() && (peek() == '+' || peek() == '-')) {
                    index++;
                }
                consumeDigits();
            }
            if (start == index) {
                throw error("expected a JSON value");
            }
            String number = text.substring(start, index);
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException longFailure) {
                try {
                    return Double.parseDouble(number);
                } catch (NumberFormatException doubleFailure) {
                    throw error("invalid number '" + number + "'");
                }
            }
        }

        void consumeDigits() {
            while (!atEnd() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
    }
}
