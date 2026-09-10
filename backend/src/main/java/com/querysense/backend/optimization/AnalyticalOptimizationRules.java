package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.SuggestionSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * AnalyticalOptimizationRules
 *
 * Analytical (OLAP-style) workloads are expected to scan large portions of
 * data by design — a full table scan alone isn't automatically a problem
 * here the way it is for TRANSACTIONAL or E-commerce point lookups. What
 * still matters: whether a filter predicate that runs *before* an
 * aggregation is indexed (an unindexed pre-filter means the aggregation
 * does far more work than necessary), and whether a heavy combination of
 * temp-table + filesort on a large row count suggests the query should be
 * backed by a precomputed summary table instead of computed live every
 * time. This mirrors the HTAP literature's point that analytical and
 * transactional workloads need distinct, non-merged rule sets.
 */
public class AnalyticalOptimizationRules implements DomainOptimizationRules {

    // Analytical queries are expected to touch more rows than a
    // transactional/e-commerce lookup, so this threshold is set higher —
    // scanning a few thousand rows for a report isn't alarming on its own.
    private static final long ROW_COUNT_WARNING_THRESHOLD = 10_000L;

    @Override
    public List<OptimizationSuggestion> evaluate(QueryAnalysisResult result) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        if ("MySQL".equalsIgnoreCase(result.getDatabaseType())) {
            evaluateMySql(result, suggestions);
        } else if ("MongoDB".equalsIgnoreCase(result.getDatabaseType())) {
            evaluateMongo(result, suggestions);
        }

        return suggestions;
    }

    private void evaluateMySql(QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        boolean rowsAreLarge = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_WARNING_THRESHOLD;

        // A full scan by itself is expected for a report. What matters is
        // whether the query had a filter predicate that could have narrowed
        // the input to the aggregation but didn't, because there's no index
        // to support it.
        if (result.isHasFullTableScan() && !result.isUsesIndex()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Full scan on '" + result.getTableName() + "' with no index used to pre-filter before "
                            + "aggregation. A full scan is often acceptable for analytical queries, but an "
                            + "indexed pre-filter would still reduce how much data reaches the aggregation step.",
                    SuggestionSeverity.WARNING
            ));
        } else if (result.isHasFullTableScan()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Full scan on '" + result.getTableName() + "', but an index is already narrowing the input "
                            + "first — expected and acceptable for this kind of aggregation query.",
                    SuggestionSeverity.INFO
            ));
        }

        // Temp table + filesort together, on a large row count, is the
        // signature of a heavy live GROUP BY / ORDER BY that's recomputed
        // from scratch every time — a candidate for a precomputed summary
        // table rather than further indexing.
        if (result.isUsesTemporaryTable() && result.isUsesFilesort() && rowsAreLarge) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query combines a temporary table and a filesort over an estimated " + result.getEstimatedRows()
                            + " rows — this aggregation is being computed live on every run. Consider a "
                            + "precomputed summary/rollup table if this report is run frequently.",
                    SuggestionSeverity.CRITICAL
            ));
        } else if (result.isUsesTemporaryTable()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query uses a temporary table, likely from GROUP BY or DISTINCT — normal for an aggregation "
                            + "query and not a concern at this row count.",
                    SuggestionSeverity.INFO
            ));
        }

        // A filesort on its own (no temp table) usually just means an ORDER
        // BY on the final aggregated result — flag only if the row count
        // going into the sort is large enough to matter.
        if (result.isUsesFilesort() && !result.isUsesTemporaryTable() && rowsAreLarge) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Filesort over a large estimated row count with no supporting index for the ORDER BY. "
                            + "Consider an index covering the sort column if this report is run often.",
                    SuggestionSeverity.WARNING
            ));
        }
    }

    private void evaluateMongo(QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        // Unindexed filters matter less here than in e-commerce — an
        // analytical aggregation pipeline is expected to touch a large
        // fraction of a collection, and these queries typically run far
        // less often than a customer-facing catalog filter. Still worth
        // flagging, just not at CRITICAL.
        if (result.isHasUnindexedFilter()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Filter on '" + result.getFilteredColumn() + "' has no supporting index. Less urgent for an "
                            + "aggregation pipeline than for a high-traffic lookup, but an index would still "
                            + "reduce the input size before the pipeline runs.",
                    SuggestionSeverity.WARNING
            ));
        }

        // Ad-hoc regex filters in analytical/reporting contexts run
        // infrequently, so this is informational rather than a warning.
        if (result.isHasUnanchoredRegex()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Unanchored regex filter detected. This can't use an index, but for an infrequently-run "
                            + "analytical query the impact is likely minor.",
                    SuggestionSeverity.INFO
            ));
        }
    }
}