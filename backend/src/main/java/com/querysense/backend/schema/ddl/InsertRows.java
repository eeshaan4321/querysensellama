package com.querysense.backend.schema.ddl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the literal rows of an INSERT ... VALUES statement. */
final class InsertRows {

    private static final Pattern HEAD = Pattern.compile(
            "^\\s*INSERT\\s+(?:IGNORE\\s+)?INTO\\s+([\\w.\"`]+)\\s*(\\([^)]*\\))?\\s*VALUES\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private static final Pattern TYPED_LITERAL = Pattern.compile("(?i)(?:DATE|TIME|TIMESTAMP)\\s*'");

    private static final Object UNREADABLE = new Object();

    private InsertRows() {}

    static Optional<SampleInsert> read(String statement) {
        Matcher head = HEAD.matcher(statement);
        if (!head.find()) {
            return Optional.empty();
        }
        String table = bare(head.group(1));
        List<String> columns = new ArrayList<>();
        if (head.group(2) != null) {
            String list = head.group(2);
            for (String column : list.substring(1, list.length() - 1).split(",")) {
                if (!column.isBlank()) {
                    columns.add(bare(column));
                }
            }
        }

        List<List<Object>> rows = new ArrayList<>();
        int skipped = 0;
        String body = statement.substring(head.end());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (c != '(') {
                break;
            }
            int close = closingParen(body, i);
            if (close < 0) {
                break;
            }
            List<Object> row = tuple(body.substring(i + 1, close));
            if (row == null) {
                skipped++;
            } else {
                rows.add(Collections.unmodifiableList(row));
            }
            i = close + 1;
        }
        return Optional.of(new SampleInsert(table, List.copyOf(columns), List.copyOf(rows), skipped));
    }

    private static List<Object> tuple(String inner) {
        List<Object> values = new ArrayList<>();
        for (String part : split(inner)) {
            Object value = literal(part.strip());
            if (value == UNREADABLE) {
                return null;
            }
            values.add(value);
        }
        return values;
    }

    private static Object literal(String token) {
        if (token.isEmpty()) {
            return UNREADABLE;
        }
        Matcher typed = TYPED_LITERAL.matcher(token);
        if (typed.lookingAt()) {
            token = token.substring(typed.end() - 1);
        }
        if (token.length() >= 2 && token.startsWith("'") && token.endsWith("'")) {
            return token.substring(1, token.length() - 1).replace("''", "'").replace("\\'", "'");
        }
        String upper = token.toUpperCase(Locale.ROOT);
        if ("NULL".equals(upper)) {
            return null;
        }
        if ("TRUE".equals(upper)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equals(upper)) {
            return Boolean.FALSE;
        }
        if (NUMBER.matcher(token).matches()) {
            return new BigDecimal(token);
        }
        return UNREADABLE;
    }

    private static List<String> split(String inner) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quoted) {
                current.append(c);
                if (c == '\'' && i + 1 < inner.length() && inner.charAt(i + 1) == '\'') {
                    current.append(inner.charAt(++i));
                } else if (c == '\'') {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static int closingParen(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == '\'') {
                    quoted = false;
                }
            } else if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String bare(String identifier) {
        String cleaned = identifier.replace("\"", "").replace("`", "").strip();
        return cleaned.substring(cleaned.lastIndexOf('.') + 1);
    }
}
