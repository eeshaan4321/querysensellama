package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
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

    private record Words(String engine, String table, String rows, boolean mongo) {
        static final Words MYSQL = new Words("MySQL", "table", "rows", false);
        static final Words MONGODB = new Words("MongoDB", "collection", "documents", true);
    }

    private static Words wordsFor(QueryAnalysisResult result) {
        String type = result.getDatabaseType();
        return type != null && !type.isBlank() && Dialect.from(type) == Dialect.MONGODB ? Words.MONGODB : Words.MYSQL;
    }

    public void apply(QueryAnalysisResult result) {
        if (result == null) {
            return;
        }
        result.setVerdict(evaluate(result));
    }

    public AnalysisVerdict evaluate(QueryAnalysisResult result) {
        Words w = wordsFor(result);
        if (result.isExplainFailed()) {
            return new AnalysisVerdict(Severity.UNKNOWN, "Couldn't analyze",
                    w.engine() + " couldn't produce an execution plan for this query.",
                    "The specific reason is listed with the warnings below. No metrics are shown, "
                            + "because none were measured — an empty plan is not a clean one.");
        }

        List<TableAnalysis> tables = result.getTables();
        if (tables == null || tables.isEmpty()) {
            return new AnalysisVerdict(Severity.INFO, "Nothing to read",
                    w.engine() + " can answer this query without reading any " + w.table() + ".",
                    "The optimiser resolved it from the query itself, so there is no work to reduce.");
        }

        TableAnalysis worstScan = worstFullScan(tables);
        TableAnalysis ignoredIndex = tableWithIgnoredIndex(tables);

        if (worstScan != null && isLarge(worstScan)) {
            Double filtered = worstScan.getFiltered();
            String name = displayNameOf(result, worstScan);
            String rows = formatCount(worstScan.getEstimatedRows());

            if (filtered != null && filtered < MAJORITY_DISCARDED_BELOW_PERCENT) {
                return new AnalysisVerdict(Severity.CRITICAL, "Needs attention",
                        "Reads the whole " + name + " " + w.table() + " — about " + rows + " " + w.rows()
                                + " — then discards " + formatPercent(100.0 - filtered) + " of what it read.",
                        secondaryDetail(result, ignoredIndex, worstScan, w));
            }

            if (filtered == null) {
                return new AnalysisVerdict(Severity.WARNING, "Could be better",
                        "Reads the whole " + name + " " + w.table() + " — about " + rows + " " + w.rows() + ".",
                        secondaryDetail(result, ignoredIndex, worstScan, w));
            }
        }

        if (ignoredIndex != null) {
            return new AnalysisVerdict(Severity.WARNING, "Could be better",
                    "An index on " + displayNameOf(result, ignoredIndex) + " was available, but " + w.engine()
                            + " chose not to use it.",
                    w.engine() + " only uses an index when it estimates that doing so is cheaper. On a small "
                            + w.table() + ", or for a query that matches most " + w.rows() + ", reading the "
                            + w.table() + " directly genuinely is cheaper.");
        }

        if ((result.isUsesFilesort() || result.isUsesTemporaryTable())
                && isLargeRowCount(result.getEstimatedRows())) {
            String what = result.isUsesFilesort() ? "sort" : (w.mongo() ? "group" : "assemble");
            String tail = !w.mongo() ? " after reading them."
                    : result.isUsesFilesort() ? " in memory after reading them."
                    : ", and the grouping spilled to disk.";
            return new AnalysisVerdict(Severity.WARNING, "Could be better",
                    w.engine() + " has to " + what + " roughly " + formatCount(result.getEstimatedRows())
                            + " " + w.rows() + tail,
                    "No index supplied the " + w.rows() + " in the order this query needs, so the "
                            + (w.mongo() ? "sorting" : "ordering") + " or grouping work happens as a separate step.");
        }

        if (worstScan != null) {
            String name = displayNameOf(result, worstScan);
            String rows = formatCount(worstScan.getEstimatedRows());
            Double filtered = worstScan.getFiltered();

            if (isLarge(worstScan)) {
                return new AnalysisVerdict(Severity.INFO, "Looks reasonable",
                        "Reads the whole " + name + " " + w.table() + " — about " + rows + " " + w.rows()
                                + " — and returns most of them.",
                        (w.mongo() ? "A collection scan" : "A full scan") + " is the efficient plan when a query "
                                + "genuinely needs this much of the " + w.table() + ", so there is little an "
                                + "index could save here.");
            }

            return new AnalysisVerdict(Severity.INFO, "Looks efficient",
                    "Reads all " + rows + " " + w.rows() + " of " + name + ", which is normal at this size.",
                    filtered != null && filtered < MAJORITY_DISCARDED_BELOW_PERCENT
                            ? "Most of those " + w.rows() + " are discarded, but the " + w.table()
                                    + " is small enough that the cost is negligible."
                            : null);
        }

        String key = result.getKeyUsed();
        return new AnalysisVerdict(Severity.INFO, "Looks efficient",
                key != null
                        ? "Uses the " + key + " index to find " + w.rows() + " instead of reading the whole "
                                + w.table() + "."
                        : w.engine() + " reaches the " + w.rows() + " it needs without scanning a whole "
                                + w.table() + ".",
                result.getEstimatedRows() != null
                        ? "About " + formatCount(result.getEstimatedRows()) + " " + w.rows()
                                + (w.mongo() ? " were examined." : " are expected to be read.")
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

    private String secondaryDetail(QueryAnalysisResult result, TableAnalysis ignoredIndex, TableAnalysis scanned, Words w) {
        if (ignoredIndex != null) {
            return w.engine() + (w.mongo() ? " considered " : " listed ") + ignoredIndex.getPossibleKeys() + " on "
                    + displayNameOf(result, ignoredIndex)
                    + " as usable for this query and then chose not to use it.";
        }
        if (scanned.getPossibleKeys() == null || scanned.getPossibleKeys().isBlank()) {
            return w.engine() + " reported no index it could have used for this " + w.table() + ".";
        }
        return null;
    }

    private boolean isLarge(TableAnalysis table) {
        return isLargeRowCount(table.getEstimatedRows());
    }

    private boolean isLargeRowCount(Long rows) {
        return rows != null && rows >= largeTableRows;
    }

    private String displayNameOf(QueryAnalysisResult result, TableAnalysis table) {
        String name = result.realTableName(table.getTableName());
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
