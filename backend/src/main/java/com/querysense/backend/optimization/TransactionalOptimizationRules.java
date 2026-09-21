package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.SuggestionSeverity;

import java.util.ArrayList;
import java.util.List;

/** Optimisation rules for transactional workloads. */
public class TransactionalOptimizationRules implements DomainOptimizationRules {

    private static final long ROW_COUNT_CRITICAL_THRESHOLD = 100L;

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

        boolean rowsExceedThreshold = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_CRITICAL_THRESHOLD;

        if (result.isHasFullTableScan()) {
            SuggestionSeverity severity = rowsExceedThreshold ? SuggestionSeverity.CRITICAL : SuggestionSeverity.WARNING;
            suggestions.add(OptimizationSuggestion.domain(
                    "Full table scan on '" + result.getTableName() + "' in a transactional query. This query "
                            + "path is expected to run frequently — a missing index here means this scan repeats "
                            + "on every call. Add an index on the lookup column(s) immediately.",
                    severity
            ));
        }

        if (result.isUsesFilesort()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query requires a filesort. Transactional queries should be simple key lookups — a sort step "
                            + "here suggests this query is doing more than it should on a hot path. Consider an "
                            + "index covering the sort order, or reconsider whether this belongs in the "
                            + "transactional path at all.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        if (result.isUsesTemporaryTable()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query uses a temporary table. This usually means a GROUP BY, DISTINCT, or subquery, which is "
                            + "unusual for a transactional query — this logic may be better suited to a batch or "
                            + "analytical path rather than running on every transactional call.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        if (!result.isUsesIndex() && result.getKeyUsed() == null && !result.isHasFullTableScan()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "No index used for this lookup on '" + result.getTableName() + "'. Transactional queries "
                            + "should resolve via an index (ideally the primary or a unique key) for consistent, "
                            + "low latency.",
                    SuggestionSeverity.WARNING
            ));
        }

        if (("const".equalsIgnoreCase(result.getAccessType()) || "ref".equalsIgnoreCase(result.getAccessType()))
                && result.getKeyUsed() != null) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query resolves via index '" + result.getKeyUsed() + "' with access type '"
                            + result.getAccessType() + "' — this is the ideal shape for a transactional lookup.",
                    SuggestionSeverity.INFO
            ));
        }
    }

    private void evaluateMongo(QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        if (result.isHasUnindexedFilter()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Filter on '" + result.getFilteredColumn() + "' has no supporting index. For a transactional "
                            + "operation running on every call, this is a critical latency risk — add an index "
                            + "on the filtered field(s) immediately.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        if (result.isHasUnanchoredRegex()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Unanchored regex filter detected in a transactional query. This forces a collection scan on "
                            + "every call — reconsider whether regex matching belongs in this code path, or "
                            + "anchor the pattern if a prefix match is sufficient.",
                    SuggestionSeverity.CRITICAL
            ));
        }
    }
}