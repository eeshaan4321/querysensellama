package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.SuggestionSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * TransactionalOptimizationRules
 *
 * Transactional (OLTP-style) queries are assumed to be simple, frequent,
 * and latency-critical — point lookups and small writes keyed off an
 * index, not scans or aggregations. Per the HTAP literature backing this
 * project, matching the rule set to workload type (rather than one
 * universal optimizer) is where most of the real performance gain comes
 * from, and transactional is the workload type least tolerant of scans,
 * sorts, or temp tables — each one repeats on every single call instead
 * of running once for a report. Thresholds here are intentionally the
 * lowest of the three profiles.
 */
public class TransactionalOptimizationRules implements DomainOptimizationRules {

    // Transactional queries are expected to touch a handful of rows via a
    // key lookup — even a modest row count here is a signal something is
    // off, unlike the higher tolerances in the e-commerce/analytical profiles.
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

        // A full scan on a transactional query almost always means a missing
        // index on what should be a simple key lookup. Unlike the other two
        // profiles, there's no "acceptable at low row count" carve-out here —
        // this pattern repeats on every call, so even a small table adds up.
        if (result.isHasFullTableScan()) {
            SuggestionSeverity severity = rowsExceedThreshold ? SuggestionSeverity.CRITICAL : SuggestionSeverity.WARNING;
            suggestions.add(OptimizationSuggestion.domain(
                    "Full table scan on '" + result.getTableName() + "' in a transactional query. This query "
                            + "path is expected to run frequently — a missing index here means this scan repeats "
                            + "on every call. Add an index on the lookup column(s) immediately.",
                    severity
            ));
        }

        // Filesort has no place in a simple transactional lookup — it means
        // the query is doing more work than a point lookup should.
        if (result.isUsesFilesort()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query requires a filesort. Transactional queries should be simple key lookups — a sort step "
                            + "here suggests this query is doing more than it should on a hot path. Consider an "
                            + "index covering the sort order, or reconsider whether this belongs in the "
                            + "transactional path at all.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        // A temporary table almost always means a GROUP BY, DISTINCT, or
        // subquery — none of which belong in a high-frequency transactional
        // call. This is a stronger signal here than in the other profiles:
        // it suggests the query may be miscategorized as transactional.
        if (result.isUsesTemporaryTable()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query uses a temporary table. This usually means a GROUP BY, DISTINCT, or subquery, which is "
                            + "unusual for a transactional query — this logic may be better suited to a batch or "
                            + "analytical path rather than running on every transactional call.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        // No index at all on the lookup — the core failure mode for a
        // transactional query, flagged even when hasFullTableScan didn't
        // already catch it (e.g. a small join without a key on one side).
        if (!result.isUsesIndex() && result.getKeyUsed() == null && !result.isHasFullTableScan()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "No index used for this lookup on '" + result.getTableName() + "'. Transactional queries "
                            + "should resolve via an index (ideally the primary or a unique key) for consistent, "
                            + "low latency.",
                    SuggestionSeverity.WARNING
            ));
        }

        // The ideal case — acknowledge it, per the project's goal of
        // explaining *why* a query is fine, not just flagging problems.
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

        // An unindexed filter on a transactional Mongo path is the same
        // failure mode as a MySQL full scan here — flagged at the highest
        // severity of the three profiles, since this runs on every call.
        if (result.isHasUnindexedFilter()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Filter on '" + result.getFilteredColumn() + "' has no supporting index. For a transactional "
                            + "operation running on every call, this is a critical latency risk — add an index "
                            + "on the filtered field(s) immediately.",
                    SuggestionSeverity.CRITICAL
            ));
        }

        // Regex scans are expensive and rarely belong in a high-frequency
        // transactional path at all — flagged more strongly than in the
        // e-commerce or analytical profiles.
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