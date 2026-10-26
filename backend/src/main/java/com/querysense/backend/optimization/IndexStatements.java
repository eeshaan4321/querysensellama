package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** The index statement a suggestion shows, written for its dialect; QuerySense never runs it. */
public final class IndexStatements {

    private IndexStatements() {
    }

    public static String forMySqlQuery(com.querysense.backend.dto.QueryAnalysisResult result, String column) {
        String table = result.getTableName();
        if (table == null || column == null) {
            return null;
        }
        int dot = column.lastIndexOf('.');
        if (dot >= 0) {
            if (!column.substring(0, dot).equalsIgnoreCase(table)) {
                return null;
            }
            column = column.substring(dot + 1);
        }
        return create(Dialect.MYSQL, result.realTableName(table), column);
    }

    public static String create(Dialect dialect, String table, String column) {
        return column == null ? null : create(dialect, table, List.of(new IndexSpec.KeyPart(column, 1)));
    }

    public static String create(Dialect dialect, String table, List<IndexSpec.KeyPart> keys) {
        if (table == null || keys == null || keys.isEmpty()
                || keys.stream().map(IndexSpec.KeyPart::column).anyMatch(Objects::isNull)) {
            return null;
        }
        if (dialect == Dialect.MONGODB) {
            return "db." + table + ".createIndex({ " + keys.stream()
                    .map(k -> "\"" + k.column() + "\": " + k.direction())
                    .collect(Collectors.joining(", ")) + " });";
        }
        List<String> columns = new java.util.ArrayList<>();
        for (IndexSpec.KeyPart key : keys) {
            String column = key.column();
            int dot = column.lastIndexOf('.');
            if (dot >= 0) {
                if (!column.substring(0, dot).equalsIgnoreCase(table)) {
                    return null;
                }
                column = column.substring(dot + 1);
            }
            columns.add(column);
        }
        return "CREATE INDEX idx_" + table + "_" + String.join("_", columns)
                + " ON " + table + "(" + String.join(", ", columns) + ");";
    }
}
