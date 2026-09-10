package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.SchemaAdvisorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SchemaAdvisorServiceImpl
 *
 * WHY THIS CLASS EXISTS:
 * Implements Objective #5 from the synopsis ("schema analysis and
 * recommendations, such as indexing, normalization, and structural
 * additions"). Every rule here reads a QueryAnalysisResult signal that
 * Anant's engine already proved is grounded in real EXPLAIN/regex
 * behavior (not invented heuristics) — see Paper #7 (DBA Bandits) in
 * CLAUDE.md, which is exactly the justification for anchoring index
 * advice to observed behavior rather than generic static rules like
 * "index every filtered column regardless of evidence."
 *
 * WHY A SINGLE FLAT METHOD INSTEAD OF ONE RULE-CLASS PER RECOMMENDATION:
 * There are five rules total (three MySQL, two MongoDB) and none of them
 * share state or need independent unit isolation yet. Matches the scale
 * QueryAnalysisServiceImpl operates at — introducing a Strategy-pattern
 * rule engine here would be solving a problem this project doesn't have
 * yet (YAGNI); if the rule count grows substantially this can be revisited.
 *
 * CONSISTENCY GUARD (Paper #8, MonoM):
 * Every rule below is gated on the specific problem flag that justifies
 * it — usesIndex being true never by itself suppresses a rule, because
 * a query can use an index for its WHERE clause and still need filesort
 * for its ORDER BY (they're independent EXPLAIN signals). What Paper #8
 * actually rules out is recommending an index for a problem that isn't
 * present — hence hasFullTableScan's rule requires BOTH the scan flag
 * AND !usesIndex, so we never suggest "add an index" when one is already
 * doing the job for that specific problem.
 */
@Service
public class SchemaAdvisorServiceImpl implements SchemaAdvisorService {

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        List<SchemaRecommendation> recommendations = new ArrayList<>();

        if ("MongoDB".equalsIgnoreCase(result.getDatabaseType())) {
            addMongoRecommendations(result, recommendations);
        } else {
            addMySqlRecommendations(result, recommendations);
        }

        return recommendations;
    }

    private void addMySqlRecommendations(QueryAnalysisResult result, List<SchemaRecommendation> recommendations) {
        if (result.isHasFullTableScan() && !result.isUsesIndex()) {
            String column = result.getFilteredColumn();
            String columnClause = column != null ? column : "<the column used in the WHERE clause>";
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    result.getTableName(),
                    column,
                    "Full table scan detected (accessType=ALL) with no index in use"
                            + (column != null ? " on '" + column + "'" : "")
                            + " — this is the same signal Anant's engine flags via EXPLAIN.",
                    "CREATE INDEX idx_" + safeName(result.getTableName(), column)
                            + " ON " + result.getTableName() + "(" + columnClause + ");"
            ));
        }

        if (result.isUsesFilesort()) {
            String column = result.getOrderByColumn();
            String filterColumn = result.getFilteredColumn();
            String columnClause = column != null ? column : "<the ORDER BY column>";

            // WHY THIS CAVEAT EXISTS: verified live during Schema Advisor
            // testing that a single-column index on the ORDER BY column does
            // NOT reliably eliminate filesort — MySQL can only use one index
            // per table access, so a query that also filters on a different
            // column (via its own index) still needs a separate sort pass
            // unless a composite index covers both. Presenting this
            // suggestion as a guaranteed fix would violate the "estimates
            // aren't guarantees" honesty rule (Paper #5, CLAUDE.md) that this
            // project already commits to for the Comparison Engine.
            String caveat = (filterColumn != null && !filterColumn.equalsIgnoreCase(column))
                    ? " Note: this query also filters on '" + filterColumn + "' — a single-column index on '"
                            + columnClause + "' alone may not eliminate the filesort, since MySQL can only use "
                            + "one index per table access; a composite index on (" + filterColumn + ", " + columnClause
                            + ") may be needed instead. Verify with EXPLAIN after applying — this is a "
                            + "starting-point heuristic, not a guaranteed fix."
                    : " This is a heuristic based on the ORDER BY column alone; MySQL's optimizer may still choose "
                            + "a full scan for small tables or non-covering queries even after the index exists — "
                            + "verify with EXPLAIN after applying rather than assuming it's fixed.";

            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    result.getTableName(),
                    column,
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
                    "Query requires a temporary table (Extra: 'Using temporary'), typically caused "
                            + "by GROUP BY or DISTINCT without a supporting index — this isn't always "
                            + "fixable with a single-column index, so it's flagged for review rather "
                            + "than an automatic CREATE INDEX suggestion.",
                    "Review the GROUP BY/DISTINCT clause on '" + result.getTableName()
                            + "'; consider a composite index covering the grouped column(s) if one column is consistently responsible."
            ));
        }
    }

    private void addMongoRecommendations(QueryAnalysisResult result, List<SchemaRecommendation> recommendations) {
        if (result.isHasUnindexedFilter()) {
            String field = result.getFilteredColumn();
            String collection = result.getTableName(); // null today — see MongoSimpleAnalyzer's collectionName gap
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    collection,
                    field,
                    "Filtering on '" + field + "' with no known index — MongoSimpleAnalyzer flagged this "
                            + "as a likely full collection scan.",
                    "db." + (collection != null ? collection : "<collection>")
                            + ".createIndex({ \"" + field + "\": 1 });"
            ));
        }

        if (result.isHasUnanchoredRegex()) {
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    result.getTableName(),
                    null,
                    "Unanchored $regex pattern detected — MongoDB cannot use an index for a regex "
                            + "that isn't left-anchored, regardless of whether an index exists on the field.",
                    "Rewrite the regex with a leading '^' if a prefix match is intended, "
                            + "or create a text index (db.<collection>.createIndex({ <field>: \"text\" })) "
                            + "if full substring search is required."
            ));
        }
    }

    /**
     * Builds a safe index-name suggestion even when the column name is
     * unknown — falls back to a generic "col" placeholder rather than
     * producing "idx_customers_null" or throwing on a null column.
     */
    private String safeName(String tableName, String column) {
        String table = tableName != null ? tableName : "table";
        String col = column != null ? column : "col";
        return table + "_" + col;
    }
}
