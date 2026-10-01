package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
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
            String sortIndex = IndexStatements.create(Dialect.MYSQL, result.getTableName(), result.getOrderByColumn());
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this filesort covers a significant row count — it is likely "
                                + "to spill to disk, which is much slower than an in-memory sort. Avoiding the "
                                + "sort (via an index) matters more under these constraints than it would otherwise.",
                        SuggestionSeverity.CRITICAL, sortIndex));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "Filesort over a significant row count. At medium available memory this is usually fine, "
                                + "but worth monitoring if memory pressure increases.",
                        SuggestionSeverity.WARNING, sortIndex));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "Filesort detected, but available memory is high — this is unlikely to cause a "
                                + "disk-based sort or noticeable slowdown.",
                        SuggestionSeverity.INFO, sortIndex));
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
                    SuggestionSeverity.WARNING,
                    IndexStatements.create(Dialect.MYSQL, result.getTableName(), result.getFilteredColumn())));
        }
    }

    private void evaluateMongo(ResourceLevel level, QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        boolean documentsAreSignificant = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_MEMORY_CONCERN_THRESHOLD;

        if (result.isUsesFilesort() && documentsAreSignificant) {
            String sortIndex = TransactionalOptimizationRules.mongoIndex(result, false);
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this in-memory sort covers a significant number of "
                                + "documents — it is likely to exceed MongoDB's sort memory limit and spill to "
                                + "disk, or fail. Avoiding the sort (via an index) matters more under these "
                                + "constraints than it would otherwise.",
                        SuggestionSeverity.CRITICAL, sortIndex));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "In-memory sort over a significant number of documents. At medium available memory "
                                + "this is usually fine, but worth monitoring if memory pressure increases.",
                        SuggestionSeverity.WARNING, sortIndex));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "In-memory sort detected, but available memory is high — this is unlikely to spill "
                                + "to disk or cause a noticeable slowdown.",
                        SuggestionSeverity.INFO, sortIndex));
            }
        }

        if (result.isUsesTemporaryTable()) {
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this grouping already spilled to disk — under memory "
                                + "pressure the spill grows, adding significant latency.",
                        SuggestionSeverity.CRITICAL));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "The grouping spilled to disk. At medium available memory this is usually fine, but "
                                + "keep an eye on it if document counts grow.",
                        SuggestionSeverity.WARNING));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "The grouping spilled to disk even though available memory is high — it is unlikely "
                                + "to be the bottleneck, but its memory limit is being reached.",
                        SuggestionSeverity.INFO));
            }
        }

        if (result.isHasFullTableScan() && documentsAreSignificant && level == ResourceLevel.LOW) {
            suggestions.add(OptimizationSuggestion.resource(
                    "Full collection scan over a significant number of documents while available memory is "
                            + "low — this pushes other data out of MongoDB's cache and can slow down other "
                            + "concurrent queries, not just this one.",
                    SuggestionSeverity.WARNING,
                    TransactionalOptimizationRules.mongoIndex(result, true)));
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
            adjusted.add(suggestion.withSeverity(newSeverity));
        }
        return adjusted;
    }

    private SuggestionSeverity shiftSeverity(SuggestionSeverity severity, int shift) {
        SuggestionSeverity[] values = SuggestionSeverity.values();
        int newOrdinal = Math.max(0, Math.min(values.length - 1, severity.ordinal() + shift));
        return values[newOrdinal];
    }
}