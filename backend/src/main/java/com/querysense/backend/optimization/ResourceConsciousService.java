package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.enums.SuggestionSeverity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ResourceConsciousService
 *
 * Unlike the Domain-Sensitive Optimization Module, this is a single class
 * rather than one strategy implementation per ResourceLevel — the logic is
 * threshold-based scaling, not fundamentally different rule sets, which
 * matches the capstone's stated design goal of keeping resource-awareness
 * simple and rule-driven rather than a black-box cost model (see "How Good
 * Are Learned Cost Models, Really?" in the literature review).
 *
 * Two responsibilities, used independently by the orchestration layer:
 *
 * 1. evaluate(...) — produces its OWN suggestions, specifically about
 *    memory/resource pressure (e.g. a filesort that will spill to disk
 *    under low available memory), grounded in the Memory-Aware Query
 *    Optimization paper cited in the literature review.
 *
 * 2. adjustSeverity(...) — re-scales the severity of suggestions the
 *    Domain-Sensitive Optimization Module already produced, so the same
 *    domain finding reads as CRITICAL under LOW resources but only INFO
 *    under HIGH resources.
 *
 * QueryGenerationServiceImpl decides how to combine these two outputs —
 * this class doesn't know about DomainOptimizationService at all.
 */
@Service
public class ResourceConsciousService {

    // Above this row count, a filesort/temp-table/full-scan starts to be a
    // real memory concern rather than a theoretical one.
    private static final long ROW_COUNT_MEMORY_CONCERN_THRESHOLD = 5_000L;

    // ------------------------------------------------------------------
    // 1. Independent resource-specific suggestions
    // ------------------------------------------------------------------

    public List<OptimizationSuggestion> evaluate(ResourceLevel resourceLevel, QueryAnalysisResult result) {
        if (result == null) {
            throw new IllegalArgumentException("QueryAnalysisResult must not be null");
        }

        ResourceLevel level = resourceLevel != null ? resourceLevel : ResourceLevel.MEDIUM;
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        if ("MySQL".equalsIgnoreCase(result.getDatabaseType())) {
            evaluateMySql(level, result, suggestions);
        } else if ("MongoDB".equalsIgnoreCase(result.getDatabaseType())) {
            evaluateMongo(level, result, suggestions);
        }

        return suggestions;
    }

    private void evaluateMySql(ResourceLevel level, QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        boolean rowsAreSignificant = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_MEMORY_CONCERN_THRESHOLD;

        // A filesort that fits in memory (sort_buffer_size) is cheap. Under
        // low available memory, larger sorts are far more likely to spill
        // to disk, which is dramatically slower than the in-memory case.
        if (result.isUsesFilesort() && rowsAreSignificant) {
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this filesort covers a significant row count — it is likely "
                                + "to spill to disk, which is much slower than an in-memory sort. Avoiding the "
                                + "sort (via an index) matters more under these constraints than it would otherwise.",
                        SuggestionSeverity.CRITICAL));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "Filesort over a significant row count. At medium available memory this is usually fine, "
                                + "but worth monitoring if memory pressure increases.",
                        SuggestionSeverity.WARNING));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "Filesort detected, but available memory is high — this is unlikely to cause a "
                                + "disk-based sort or noticeable slowdown.",
                        SuggestionSeverity.INFO));
            }
        }

        // Temporary tables (from GROUP BY/DISTINCT/subqueries) consume
        // working memory directly — same reasoning as filesort above.
        if (result.isUsesTemporaryTable()) {
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this query builds a temporary table — under memory "
                                + "pressure this is more likely to be written to disk rather than held in memory, "
                                + "adding significant latency.",
                        SuggestionSeverity.CRITICAL));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "Query builds a temporary table. Acceptable at medium available memory, but keep an eye "
                                + "on it if row counts grow.",
                        SuggestionSeverity.WARNING));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "Temporary table required, but available memory is high — unlikely to be a bottleneck.",
                        SuggestionSeverity.INFO));
            }
        }

        // A large full scan competes for buffer pool space regardless of
        // domain — flagged here specifically from a memory-pressure angle,
        // separate from the domain module's own full-scan finding.
        if (result.isHasFullTableScan() && rowsAreSignificant && level == ResourceLevel.LOW) {
            suggestions.add(OptimizationSuggestion.resource(
                    "Full table scan over a significant row count while available memory is low — this "
                            + "increases pressure on the buffer pool and can slow down other concurrent queries, "
                            + "not just this one.",
                    SuggestionSeverity.WARNING));
        }
    }

    private void evaluateMongo(ResourceLevel level, QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        // An unindexed filter forces a full collection scan, which under
        // low available memory competes with MongoDB's WiredTiger cache
        // for the same working set as every other query.
        if (result.isHasUnindexedFilter() && level == ResourceLevel.LOW) {
            suggestions.add(OptimizationSuggestion.resource(
                    "Unindexed filter combined with low available memory — the resulting collection scan "
                            + "competes for cache space with other queries, compounding the performance impact "
                            + "beyond just this one operation.",
                    SuggestionSeverity.CRITICAL));
        }
    }

    // ------------------------------------------------------------------
    // 2. Severity re-scaling for suggestions from the Domain module
    // ------------------------------------------------------------------

    /**
     * Returns a new list with each suggestion's severity shifted based on
     * resource level: escalated under LOW, unchanged under MEDIUM, and
     * de-escalated under HIGH. Does not mutate the input list.
     */
    public List<OptimizationSuggestion> adjustSeverity(ResourceLevel resourceLevel, List<OptimizationSuggestion> suggestions) {
        if (suggestions == null) {
            return new ArrayList<>();
        }

        ResourceLevel level = resourceLevel != null ? resourceLevel : ResourceLevel.MEDIUM;
        int shift = switch (level) {
            case LOW -> 1;
            case MEDIUM -> 0;
            case HIGH -> -1;
        };

        if (shift == 0) {
            return suggestions;
        }

        List<OptimizationSuggestion> adjusted = new ArrayList<>();
        for (OptimizationSuggestion suggestion : suggestions) {
            SuggestionSeverity newSeverity = shiftSeverity(suggestion.getSeverity(), shift);
            adjusted.add(new OptimizationSuggestion(suggestion.getMessage(), newSeverity, suggestion.getSource()));
        }
        return adjusted;
    }

    private SuggestionSeverity shiftSeverity(SuggestionSeverity severity, int shift) {
        SuggestionSeverity[] values = SuggestionSeverity.values(); // ordinal order: INFO, WARNING, CRITICAL
        int newOrdinal = Math.max(0, Math.min(values.length - 1, severity.ordinal() + shift));
        return values[newOrdinal];
    }
}