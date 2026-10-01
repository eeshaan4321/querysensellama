package com.querysense.backend.advisor;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Schema recommendations for MySQL: indexes for full scans and filesorts, and grouping flagged for review. */
@Component
public class MySqlIndexAdvisor implements IndexAdvisor {

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        List<SchemaRecommendation> recommendations = new ArrayList<>();
        addMySqlRecommendations(result, recommendations);
        return recommendations;
    }

    private void addMySqlRecommendations(QueryAnalysisResult result, List<SchemaRecommendation> recommendations) {
        if (result.isHasFullTableScan() && !result.isUsesIndex()) {
            String column = result.getFilteredColumn();
            String columnClause = column != null ? column : "<the column used in the WHERE clause>";
            String table = tableLabel(result.getTableName());

            String summary = "To answer this, the database is reading " + rowsPhrase(result)
                    + " of " + table + " and checking each one"
                    + (column != null ? ", because nothing indexes " + column : "")
                    + ". An index works like the index at the back of a book: it lets the "
                    + "database jump straight to the matching rows instead of reading everything.";

            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    result.getTableName(),
                    column,
                    summary,
                    "Full table scan detected (accessType=ALL) with no index in use"
                            + (column != null ? " on '" + column + "'" : "")
                            + " — the same signal reported by EXPLAIN.",
                    "CREATE INDEX idx_" + safeName(result.getTableName(), column)
                            + " ON " + result.getTableName() + "(" + columnClause + ");"
            ));
        }

        if (result.isUsesFilesort()) {
            String column = result.getOrderByColumn();
            String filterColumn = result.getFilteredColumn();
            String columnClause = column != null ? column : "<the ORDER BY column>";

            String caveat = (filterColumn != null && !filterColumn.equalsIgnoreCase(column))
                    ? " Note: this query also filters on '" + filterColumn + "' — a single-column index on '"
                            + columnClause + "' alone may not eliminate the filesort, since MySQL can only use "
                            + "one index per table access; a composite index on (" + filterColumn + ", " + columnClause
                            + ") may be needed instead. Verify with EXPLAIN after applying — this is a "
                            + "starting-point heuristic, not a guaranteed fix."
                    : " This is a heuristic based on the ORDER BY column alone; MySQL's optimizer may still choose "
                            + "a full scan for small tables or non-covering queries even after the index exists — "
                            + "verify with EXPLAIN after applying rather than assuming it's fixed.";

            String summary = "The database can find the rows, but then has to put them in order "
                    + "in a second pass"
                    + (column != null ? ", because nothing keeps " + column + " already sorted" : "")
                    + ". On a big table that ordering step is usually the slow part. Adding an "
                    + "index on the sort column often removes it"
                    + (filterColumn != null && !filterColumn.equalsIgnoreCase(column)
                            ? ", though not always here: this query also narrows by " + filterColumn
                              + ", and the database can only use one index per table at a time. "
                              + "One index covering both columns together may be what it needs."
                            : ", though not always.")
                    + " Re-check after applying rather than assuming it worked.";

            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    result.getTableName(),
                    column,
                    summary,
                    "Query requires an extra sorting pass (Extra: 'Using filesort')"
                            + (column != null ? " for ORDER BY '" + column + "'" : "")
                            + " — an index matching the sort column can often (not always) avoid this."
                            + caveat,
                    "CREATE INDEX idx_" + safeName(result.getTableName(), column)
                            + " ON " + result.getTableName() + "(" + columnClause + ");"
            ));
        }

        if (result.isUsesTemporaryTable()) {
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    result.getTableName(),
                    null,
                    "The database is building a scratch working table behind the scenes to combine "
                            + "these rows, which usually means the columns being combined on are not "
                            + "indexed. There is no single statement that reliably fixes this, so it "
                            + "is flagged for a person to look at rather than given a ready command.",
                    "Query requires a temporary table (Extra: 'Using temporary'), typically caused "
                            + "by GROUP BY or DISTINCT without a supporting index — this isn't always "
                            + "fixable with a single-column index, so it's flagged for review rather "
                            + "than an automatic CREATE INDEX suggestion.",
                    "Review the GROUP BY/DISTINCT clause on '" + result.getTableName()
                            + "'; consider a composite index covering the grouped column(s) if one column is consistently responsible."
            ));
        }

        reconcileIndexes(recommendations);
    }

    private void reconcileIndexes(List<SchemaRecommendation> recommendations) {
        List<SchemaRecommendation> indexes = recommendations.stream()
                .filter(r -> "INDEX".equals(r.getType()))
                .toList();

        if (indexes.size() < 2) {
            return;
        }

        SchemaRecommendation filterIndex = indexes.get(0);
        SchemaRecommendation sortIndex = indexes.get(1);

        String table = filterIndex.getTargetTable();
        if (table == null || !table.equals(sortIndex.getTargetTable())) {
            return;
        }

        String filterColumn = filterIndex.getTargetColumn();
        String sortColumn = sortIndex.getTargetColumn();
        recommendations.removeAll(indexes);

        if (filterColumn == null || sortColumn == null || filterColumn.equalsIgnoreCase(sortColumn)) {
            recommendations.add(0, filterIndex);
            return;
        }

        recommendations.add(0, new SchemaRecommendation(
                "INDEX",
                table,
                filterColumn + ", " + sortColumn,
                "This query narrows the rows down by " + filterColumn + " and then puts what is "
                        + "left in order by " + sortColumn + ". The database can only use one index "
                        + "per table at a time, so two separate indexes would not both get used. "
                        + "One index covering both columns, in this order, is what it needs. "
                        + "Re-check after applying rather than assuming it worked.",
                "Two signals were reported for '" + table + "': a full table scan filtering on '"
                        + filterColumn + "', and an extra sorting pass for ORDER BY '" + sortColumn
                        + "'. MySQL uses one index per table access, so the two single-column "
                        + "indexes those rules propose separately cannot both apply — a composite "
                        + "index on (" + filterColumn + ", " + sortColumn + ") is the one that can "
                        + "serve both. Column order matters: the filtered column leads. Verify with "
                        + "EXPLAIN after applying.",
                "CREATE INDEX idx_" + safeName(table, filterColumn + "_" + sortColumn)
                        + " ON " + table + "(" + filterColumn + ", " + sortColumn + ");"
        ));
    }

    private String rowsPhrase(QueryAnalysisResult result) {
        Long rows = result.getEstimatedRows();
        if (rows == null || rows <= 0) {
            return "every row";
        }
        return "all " + String.format(Locale.UK, "%,d", rows) + " rows";
    }

    private String tableLabel(String tableName) {
        return tableName == null ? "this table" : tableName;
    }

    private String safeName(String tableName, String column) {
        String table = tableName != null ? tableName : "table";
        String col = column != null ? column : "col";
        return table + "_" + col;
    }
}
