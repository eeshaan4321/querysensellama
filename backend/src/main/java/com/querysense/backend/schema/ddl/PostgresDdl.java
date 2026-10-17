package com.querysense.backend.schema.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites PostgreSQL-only parts of a CREATE TABLE into what the MySQL upload path reads. */
final class PostgresDdl {

    private static final Pattern REFERENTIAL_ACTION = Pattern.compile(
            "\\s+ON\\s+(?:DELETE|UPDATE)\\s+(?:SET\\s+NULL|SET\\s+DEFAULT|CASCADE|RESTRICT|NO\\s+ACTION)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DEFERRABLE = Pattern.compile(
            "\\s+(?:NOT\\s+)?DEFERRABLE(?:\\s+INITIALLY\\s+(?:DEFERRED|IMMEDIATE))?|\\s+INITIALLY\\s+(?:DEFERRED|IMMEDIATE)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MATCH_TYPE = Pattern.compile("\\s+MATCH\\s+(?:FULL|SIMPLE|PARTIAL)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IDENTITY = Pattern.compile(
            "\\bGENERATED\\s+(?:ALWAYS|BY\\s+DEFAULT)\\s+AS\\s+IDENTITY(?:\\s*\\([^)]*\\))?", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXCLUDE = Pattern.compile("^(?:CONSTRAINT\\s+\\S+\\s+)?EXCLUDE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPUTED = Pattern.compile(
            "^[\"`]?(\\w+)[\"`]?\\s.*\\bGENERATED\\s+ALWAYS\\s+AS\\s*\\(.*\\)\\s*(?:STORED|VIRTUAL)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TABLE_NAME = Pattern.compile(
            "^\\s*CREATE\\s+(?:\\w+\\s+)*?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`]+)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("TIMESTAMPTZ", "TIMESTAMP"), Map.entry("TIMESTAMP WITH TIME ZONE", "TIMESTAMP"),
            Map.entry("TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP"), Map.entry("TIMETZ", "TIME"),
            Map.entry("TIME WITH TIME ZONE", "TIME"), Map.entry("TIME WITHOUT TIME ZONE", "TIME"),
            Map.entry("DOUBLE PRECISION", "DOUBLE"), Map.entry("FLOAT8", "DOUBLE"), Map.entry("FLOAT4", "FLOAT"),
            Map.entry("INT2", "SMALLINT"), Map.entry("INT4", "INT"), Map.entry("INT8", "BIGINT"),
            Map.entry("SERIAL", "INT"), Map.entry("SERIAL4", "INT"), Map.entry("SMALLSERIAL", "SMALLINT"),
            Map.entry("SERIAL2", "SMALLINT"), Map.entry("BIGSERIAL", "BIGINT"), Map.entry("SERIAL8", "BIGINT"),
            Map.entry("BOOL", "BOOLEAN"), Map.entry("CHARACTER VARYING", "VARCHAR"), Map.entry("CHARACTER", "CHAR"),
            Map.entry("CITEXT", "VARCHAR"), Map.entry("UUID", "CHAR"), Map.entry("BYTEA", "BLOB"));

    private static final Set<String> SERIALS = Set.of("SERIAL", "SERIAL4", "SMALLSERIAL", "SERIAL2", "BIGSERIAL", "SERIAL8");

    private static final Set<String> STORED_AS_TEXT = Set.of(
            "JSONB", "TSRANGE", "TSTZRANGE", "DATERANGE", "INT4RANGE", "INT8RANGE", "NUMRANGE", "INTERVAL", "MONEY",
            "INET", "CIDR", "MACADDR", "MACADDR8", "XML", "TSVECTOR", "TSQUERY", "BOX", "LINE", "LSEG", "PATH",
            "CIRCLE", "OID", "VARBIT", "BIT VARYING", "HSTORE");

    static final Set<String> TEXT_TYPES = Set.of("TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT");

    private PostgresDdl() {}

    record MappedType(String type, List<String> args, boolean serial, boolean storedAsText) {}

    static String tableName(String statement) {
        Matcher m = TABLE_NAME.matcher(statement);
        if (!m.find()) {
            return "";
        }
        String name = m.group(1).replace("\"", "").replace("`", "");
        return name.substring(name.lastIndexOf('.') + 1);
    }

    static String clean(String statement, List<String> warnings) {
        int open = statement.indexOf('(');
        int close = matchingParen(statement, open);
        if (open < 0 || close < 0) {
            return statement;
        }
        String table = tableName(statement);
        List<String> kept = new ArrayList<>();
        for (String element : topLevelElements(statement.substring(open + 1, close))) {
            String trimmed = element.strip();
            if (EXCLUDE.matcher(trimmed).find()) {
                continue;
            }
            Matcher computed = COMPUTED.matcher(trimmed);
            if (computed.matches() && !IDENTITY.matcher(trimmed).find()) {
                warnings.add("Left out " + table + "." + computed.group(1) + ": it is computed from other columns, "
                        + "so it is not created in the sample database.");
                continue;
            }
            String rewritten = IDENTITY.matcher(trimmed).replaceAll("AUTO_INCREMENT");
            rewritten = REFERENTIAL_ACTION.matcher(rewritten).replaceAll("");
            rewritten = DEFERRABLE.matcher(rewritten).replaceAll("");
            rewritten = MATCH_TYPE.matcher(rewritten).replaceAll("");
            kept.add(rewritten);
        }
        return statement.substring(0, open + 1) + "\n  " + String.join(",\n  ", kept) + "\n" + statement.substring(close);
    }

    static MappedType mapType(String type, List<String> args) {
        String upper = type.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (upper.endsWith("[]") || STORED_AS_TEXT.contains(upper)) {
            return new MappedType("TEXT", List.of(), false, true);
        }
        if ("UUID".equals(upper)) {
            return new MappedType("CHAR", List.of("36"), false, false);
        }
        if ("CITEXT".equals(upper)) {
            return new MappedType("VARCHAR", List.of("255"), false, false);
        }
        String mapped = TYPES.get(upper);
        if (mapped == null) {
            return new MappedType(type, args, false, false);
        }
        List<String> keptArgs = "VARCHAR".equals(mapped) || "CHAR".equals(mapped) ? args : List.of();
        return new MappedType(mapped, keptArgs, SERIALS.contains(upper), false);
    }

    private static int matchingParen(String text, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> topLevelElements(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString());
        }
        return out;
    }
}
