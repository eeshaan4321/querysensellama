package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.enums.SuggestionSeverity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Resource-level suggestions and severity adjustment. */
@Service
public class ResourceConsciousService {

    private static final long ROW_COUNT_MEMORY_CONCERN_THRESHOLD = 5_000L;

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

        if (result.isHasFullTableScan() && rowsAreSignificant && level == ResourceLevel.LOW) {
            suggestions.add(OptimizationSuggestion.resource(
                    "Full table scan over a significant row count while available memory is low — this "
                            + "increases pressure on the buffer pool and can slow down other concurrent queries, "
                            + "not just this one.",
                    SuggestionSeverity.WARNING));
        }
    }

    private void evaluateMongo(ResourceLevel level, QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        if (result.isHasUnindexedFilter() && level == ResourceLevel.LOW) {
            suggestions.add(OptimizationSuggestion.resource(
                    "Unindexed filter combined with low available memory — the resulting collection scan "
                            + "competes for cache space with other queries, compounding the performance impact "
                            + "beyond just this one operation.",
                    SuggestionSeverity.CRITICAL));
        }
    }

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
        SuggestionSeverity[] values = SuggestionSeverity.values();
        int newOrdinal = Math.max(0, Math.min(values.length - 1, severity.ordinal() + shift));
        return values[newOrdinal];
    }
}