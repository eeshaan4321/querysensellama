package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.SuggestionSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * EcommerceOptimizationRules
 *
 * E-commerce workloads mix OLTP-style point lookups (product page loads,
 * cart/order operations) with OLAP-style aggregations (best-sellers,
 * category rollups) — per the PostgreSQL-vs-MongoDB e-commerce study cited
 * in the literature review, this is exactly why e-commerce gets its own
 * profile instead of being folded into TRANSACTIONAL or ANALYTICAL. These
 * rules are tuned for that mix: strict about missing indexes on filtered
 * columns (catalog browsing is latency-sensitive and high-traffic), but
 * more tolerant of range scans and sorts than the pure TRANSACTIONAL
 * profile would be.
 */
public class EcommerceOptimizationRules implements DomainOptimizationRules {

    // Below this, a full scan is cheap enough not to worry about even for
    // a latency-sensitive page. Above it, flag it. Tune as needed.
    private static final long ROW_COUNT_WARNING_THRESHOLD = 1000L;

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

        boolean rowsAreSignificant = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_WARNING_THRESHOLD;

        // Full table scan on a customer-facing lookup is the worst case for
        // e-commerce: product pages, cart lookups, and order history all
        // depend on this being fast.
        if (result.isHasFullTableScan() && rowsAreSignificant) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Full table scan detected on '" + result.getTableName() + "' over an estimated "
                            + result.getEstimatedRows() + " rows. For e-commerce catalog or order lookups, "
                            + "this directly slows down customer-facing pages — add an index on the filtered column(s).",
                    SuggestionSeverity.CRITICAL
            ));
        } else if (result.isHasFullTableScan()) {
            // Small table — still worth a note, but not urgent.
            suggestions.add(OptimizationSuggestion.domain(
                    "Full table scan detected on '" + result.getTableName() + "', but the estimated row count is low, "
                            + "so the performance impact is likely minor for now.",
                    SuggestionSeverity.INFO
            ));
        }

        // Filesort shows up a lot in e-commerce from "sort by price / rating /
        // newest" on listing pages — common enough to warrant a composite
        // index recommendation rather than a generic warning.
        if (result.isUsesFilesort()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query requires a filesort — common on product listing 'sort by' features. "
                            + "Consider a composite index covering both the filter and sort columns to avoid this.",
                    SuggestionSeverity.WARNING
            ));
        }

        // Temporary tables usually mean a GROUP BY / DISTINCT / subquery that
        // isn't served by an index — relevant for e-commerce analytics
        // widgets (e.g. "best sellers this week") layered onto the same schema.
        if (result.isUsesTemporaryTable()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query uses a temporary table, likely from a GROUP BY, DISTINCT, or subquery without index "
                            + "support. If this powers an aggregation widget, consider a covering index or a "
                            + "precomputed summary table instead of computing it live.",
                    SuggestionSeverity.WARNING
            ));
        }

        // A range scan (price ranges, date filters) is normal and expected in
        // e-commerce — acknowledge it rather than staying silent, since the
        // literature review's point about transparency (explain *why*, not
        // just flag problems) applies to the healthy cases too.
        if ("range".equalsIgnoreCase(result.getAccessType()) && result.getKeyUsed() != null) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query uses a range scan on index '" + result.getKeyUsed() + "' — typical and acceptable for "
                            + "price/date-range filters on catalog pages.",
                    SuggestionSeverity.INFO
            ));
        }
    }

    private void evaluateMongo(QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        // An unindexed filter on a product/catalog collection is the single
        // most damaging issue for an e-commerce Mongo deployment — every
        // catalog browse or search hits this same collection repeatedly.
        if (result.isHasUnindexedFilter()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Filter on '" + result.getFilteredColumn() + "' has no supporting index. For a product/catalog "
                            + "collection, this means every browse or filter request forces a full collection scan "
                            + "— add an index on the filtered field(s) as a priority.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        // Unanchored regex often shows up in e-commerce "search-as-you-type"
        // product name search — flag it, since it can't use an index at all.
        if (result.isHasUnanchoredRegex()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Unanchored regex filter detected — likely a product name/description search. This pattern "
                            + "cannot use an index. Consider anchoring the regex (^prefix) or switching to a "
                            + "MongoDB text index for search functionality.",
                    SuggestionSeverity.WARNING
            ));
        }
    }
}