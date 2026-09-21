package com.querysense.backend.analysis;

import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.AnalysisVerdict.Severity;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Turns a query analysis into a single plain-English verdict. */
@Component
public class VerdictService {

    private final long largeTableRows;

    private static final double MAJORITY_DISCARDED_BELOW_PERCENT = 50.0;

    public VerdictService(@Value("${querysense.verdict.large-table-rows:1000}") long largeTableRows) {
        this.largeTableRows = largeTableRows;
    }

    public void apply(QueryAnalysisResult result) {
        if (result == null) {
            return;
        }
        result.setVerdict(evaluate(result));
    }

    public AnalysisVerdict evaluate(QueryAnalysisResult result) {
        if (result.isExplainFailed()) {
            return new AnalysisVerdict(Severity.UNKNOWN, "Couldn't analyze",
                    "MySQL couldn't produce an execution plan for this query.",
                    "The specific reason is listed with the warnings below. No metrics are shown, "
                            + "because none were measured — an empty plan is not a clean one.");
        }

        List<TableAnalysis> tables = result.getTables();
        if (tables == null || tables.isEmpty()) {
            return new AnalysisVerdict(Severity.INFO, "Nothing to read",
                    "MySQL can answer this query without reading any table.",
                    "The optimiser resolved it from the query itself, so there is no work to reduce.");
        }

        TableAnalysis worstScan = worstFullScan(tables);
        TableAnalysis ignoredIndex = tableWithIgnoredIndex(tables);

        if (worstScan != null && isLarge(worstScan)) {
            Double filtered = worstScan.getFiltered();
            String name = displayNameOf(worstScan);
            String rows = formatCount(worstScan.getEstimatedRows());

            if (filtered != null && filtered < MAJORITY_DISCARDED_BELOW_PERCENT) {
                return new AnalysisVerdict(Severity.CRITICAL, "Needs attention",
                        "Reads the whole " + name + " table — about " + rows
                                + " rows — then discards " + formatPercent(100.0 - filtered)
                                + " of what it read.",
                        secondaryDetail(ignoredIndex, worstScan));
            }

            if (filtered == null) {
                return new AnalysisVerdict(Severity.WARNING, "Could be better",
                        "Reads the whole " + name + " table — about " + rows + " rows.",
                        secondaryDetail(ignoredIndex, worstScan));
            }
        }

        if (ignoredIndex != null) {
            return new AnalysisVerdict(Severity.WARNING, "Could be better",
                    "An index on " + displayNameOf(ignoredIndex) + " was available, but MySQL chose not to use it.",
                    "MySQL only uses an index when it estimates that doing so is cheaper. On a small "
                            + "table, or for a query that matches most rows, reading the table directly "
                            + "genuinely is cheaper.");
        }

        if ((result.isUsesFilesort() || result.isUsesTemporaryTable())
                && isLargeRowCount(result.getEstimatedRows())) {
            String what = result.isUsesFilesort() ? "sort" : "assemble";
            return new AnalysisVerdict(Severity.WARNING, "Could be better",
                    "MySQL has to " + what + " roughly " + formatCount(result.getEstimatedRows())
                            + " rows after reading them.",
                    "No index supplied the rows in the order this query needs, so the ordering or "
                            + "grouping work happens as a separate step.");
        }

        if (worstScan != null) {
            String name = displayNameOf(worstScan);
            String rows = formatCount(worstScan.getEstimatedRows());
            Double filtered = worstScan.getFiltered();

            if (isLarge(worstScan)) {
                return new AnalysisVerdict(Severity.INFO, "Looks reasonable",
                        "Reads the whole " + name + " table — about " + rows
                                + " rows — and returns most of them.",
                        "A full scan is the efficient plan when a query genuinely needs this much of "
                                + "the table, so there is little an index could save here.");
            }

            return new AnalysisVerdict(Severity.INFO, "Looks efficient",
                    "Reads all " + rows + " rows of " + name + ", which is normal at this size.",
                    filtered != null && filtered < MAJORITY_DISCARDED_BELOW_PERCENT
                            ? "Most of those rows are discarded, but the table is small enough that the "
                                    + "cost is negligible."
                            : null);
        }

        String key = result.getKeyUsed();
        return new AnalysisVerdict(Severity.INFO, "Looks efficient",
                key != null
                        ? "Uses the " + key + " index to find rows instead of reading the whole table."
                        : "MySQL reaches the rows it needs without scanning a whole table.",
                result.getEstimatedRows() != null
                        ? "About " + formatCount(result.getEstimatedRows()) + " rows are expected to be read."
                        : null);
    }

    private TableAnalysis worstFullScan(List<TableAnalysis> tables) {
        TableAnalysis worst = null;
        for (TableAnalysis table : tables) {
            if (!table.isFullTableScan()) {
                continue;
            }
            if (worst == null) {
                worst = table;
                continue;
            }
            Long rows = table.getEstimatedRows();
            Long worstRows = worst.getEstimatedRows();
            if (rows != null && (worstRows == null || rows > worstRows)) {
                worst = table;
            }
        }
        return worst;
    }

    private TableAnalysis tableWithIgnoredIndex(List<TableAnalysis> tables) {
        for (TableAnalysis table : tables) {
            if (!table.isUsesIndex() && table.getPossibleKeys() != null
                    && !table.getPossibleKeys().isBlank()) {
                return table;
            }
        }
        return null;
    }

    private String secondaryDetail(TableAnalysis ignoredIndex, TableAnalysis scanned) {
        if (ignoredIndex != null) {
            return "MySQL listed " + ignoredIndex.getPossibleKeys() + " on "
                    + displayNameOf(ignoredIndex)
                    + " as usable for this query and then chose not to use it.";
        }
        if (scanned.getPossibleKeys() == null || scanned.getPossibleKeys().isBlank()) {
            return "MySQL reported no index it could have used for this table.";
        }
        return null;
    }

    private boolean isLarge(TableAnalysis table) {
        return isLargeRowCount(table.getEstimatedRows());
    }

    private boolean isLargeRowCount(Long rows) {
        return rows != null && rows >= largeTableRows;
    }

    private String displayNameOf(TableAnalysis table) {
        String name = table.getTableName();
        return name != null && !name.isBlank() ? name : "this query's";
    }

    private String formatCount(Long rows) {
        return rows == null ? "an unreported number of" : String.format(Locale.ROOT, "%,d", rows);
    }

    private String formatPercent(double percent) {
        if (percent >= 99.95) {
            return "over 99.9%";
        }
        String formatted = String.format(Locale.ROOT, "%.1f", percent);
        return (formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted) + "%";
    }
}
