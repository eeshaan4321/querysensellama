package com.querysense.backend.schema.ddl;

import java.util.ArrayList;
import java.util.List;

/**
 * SqlStatementSplitter
 *
 * WHY THIS CLASS EXISTS:
 * JSqlParser's parseStatements() fails the ENTIRE input if any one
 * statement in it is unparseable. A real mysqldump file is full of
 * statements it has never claimed to support — executable comments
 * (/*!40101 ... *&#47;), LOCK TABLES, DELIMITER — so handing it a whole dump
 * means getting nothing back at all. Splitting first and parsing each
 * statement on its own is what lets one exotic statement cost the user that
 * statement instead of the entire upload.
 *
 * WHY NOT split(";"):
 * A dump contains semicolons inside string literals, inside backticked
 * identifiers, and inside comments. Every one of those would produce a
 * fragment that is not a statement, and the CREATE TABLE that got cut in
 * half would be reported to the user as unparseable — a confusing lie about
 * their file.
 *
 * SCOPE: this is a lexical splitter, not a parser. It knows only enough to
 * decide whether a semicolon terminates a statement: single quotes, double
 * quotes, backticks, backslash escapes, -- and # line comments, and block
 * comments. That is sufficient for the job and deliberately no more.
 */
public final class SqlStatementSplitter {

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
