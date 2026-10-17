package com.querysense.backend.schema.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits a SQL script into individual statements. */
public final class SqlStatementSplitter {

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$[A-Za-z_]*\\$");

    private SqlStatementSplitter() {}

    public static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && next != 0) {
                    current.append(next);
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '#') {
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }

            if (c == '$') {
                Matcher tag = DOLLAR_TAG.matcher(sql).region(i, sql.length());
                if (tag.lookingAt()) {
                    int end = sql.indexOf(tag.group(), tag.end());
                    int stop = end < 0 ? sql.length() : end + tag.group().length();
                    current.append(sql, i, stop);
                    i = stop - 1;
                    continue;
                }
            }

            if (c == ';') {
                addIfPresent(statements, current);
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        addIfPresent(statements, current);
        return statements;
    }

    private static void addIfPresent(List<String> statements, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }
}
