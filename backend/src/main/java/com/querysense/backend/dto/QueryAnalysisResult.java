package com.querysense.backend.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * QueryAnalysisResult
 *
 * WHY THIS CLASS EXISTS:
 * This is the shared contract between the Query Analysis Engine (Anant) and
 * every module downstream of it — Domain-Sensitive Optimization (Eeshaan),
 * Resource-Conscious Decision System (Eeshaan), Schema Advisor (Vedant),
 * and the Comparison Engine (Vedant).
 *
 * The idea: those modules don't need the real MySQL EXPLAIN / JSQL Parser
 * logic finished to start building. They can construct a QueryAnalysisResult
 * by hand (mock data) and write their rule logic against this shape today.
 * When Anant's real analyzer is done, it plugs in without anyone else's
 * code changing — same principle as QueryGenerationService being an
 * interface the controller depends on, not a concrete class.
 *
 * FIELD NOTES:
 * - MySQL-specific fields (accessType, usesFilesort, etc.) will simply stay
 *   at their default/false values for MongoDB results, and vice versa for
 *   hasUnindexedFilter/hasUnanchoredRegex. Callers should check databaseType
 *   before reading dialect-specific fields.
 * - warnings should be complete, human-readable sentences, not error codes —
 *   downstream modules (and the frontend) may surface these directly.
 */
public class QueryAnalysisResult {

    private String tableName;
    private String queryType;        // SELECT, DELETE, COUNT, etc.
    private String databaseType;     // "MySQL" or "MongoDB"

    // ---- EXPLAIN-derived signals, QUERY LEVEL (MySQL only) ----
    // WHY THESE ARE "QUERY LEVEL" AND WHAT EACH ONE NOW MEANS:
    // MySQL's EXPLAIN emits one row per table. These fields summarise the
    // whole plan into one answer, because that is what every existing
    // consumer (the three DomainOptimizationRules, ResourceConsciousService,
    // SchemaAdvisorServiceImpl, and the frontend's plan reconstruction) was
    // already written against. They are kept, and their meaning is now
    // stated rather than left to whichever EXPLAIN row happened to be last:
    //
    //   estimatedRows      largest row estimate in the plan (worst branch)
    //   accessType         WORST access type in the plan (ALL beats range
    //                      beats ref ...), so "this query does a full scan"
    //                      is never hidden by a later, cheaper table
    //   usesIndex          true if ANY table used an index
    //   hasFullTableScan   true if ANY table was scanned in full
    //   usesFilesort       true if ANY row reported "Using filesort"
    //   usesTemporaryTable true if ANY row reported "Using temporary"
    //   keyUsed            the FIRST index used in plan order (null if none;
    //                      non-null exactly when usesIndex is true)
    //
    // Per-table detail lives in `tables` below — read that when you need to
    // know WHICH table, rather than WHETHER.
    private Long estimatedRows;
    private String accessType;       // ALL, ref, index, range, const...
    private boolean usesIndex;
    private boolean hasFullTableScan;
    private boolean usesFilesort;
    private boolean usesTemporaryTable;
    private String keyUsed;          // which index, if any (null if none)

    // ---- EXPLAIN-derived signals, PER TABLE (MySQL only) ----
    // WHY THIS EXISTS: the summary fields above answer "does this query have
    // a problem"; a JOIN needs "which table has it". One entry per EXPLAIN
    // row, in plan order. Empty for MongoDB, and empty when EXPLAIN failed.
    private List<TableAnalysis> tables = new ArrayList<>();

    // ---- Simple rule-based signals (MongoDB only) ----
    private boolean hasUnindexedFilter;
    private boolean hasUnanchoredRegex;

    // ---- Column-level signals (populated where structurally determinable) ----
    // WHY these exist: hasFullTableScan/usesFilesort/hasUnindexedFilter tell
    // downstream modules THAT something is wrong, but the Schema Advisor
    // needs to know WHICH column to name in a CREATE INDEX suggestion. Rather
    // than have the Schema Advisor re-parse the raw query string itself
    // (duplicating SqlStructureParser's JSQL Parser logic), these are
    // populated once, here, by the modules that already build the AST/regex
    // match — same "parse once" principle as tableName/queryType.
    private String filteredColumn;   // first WHERE-clause column (MySQL) or offending filter field (MongoDB); null if not determinable
    private String orderByColumn;    // first ORDER BY column (MySQL only); null if no ORDER BY or not determinable

    // ---- Shared across both dialects ----
    private List<String> warnings = new ArrayList<>();

    // WHY this exists (not just leaving accessType/keyUsed/estimatedRows null):
    // null is ambiguous — it means "EXPLAIN never ran because this is a Mongo
    // query" for a Mongo result, but "EXPLAIN ran and failed" for a MySQL one.
    // The frontend can't tell those apart from null alone, so it rendered a
    // bare "—" for both. This flag makes the failure case explicit so the UI
    // can show "Unavailable — EXPLAIN failed" instead of a silent dash; the
    // human-readable reason itself still lives in warnings, per Section 11's
    // convention.
    private boolean explainFailed;

    public QueryAnalysisResult() {}

    // -----------------------------------------------------------------
    // Getters and setters
    // -----------------------------------------------------------------

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public Long getEstimatedRows() { return estimatedRows; }
    public void setEstimatedRows(Long estimatedRows) { this.estimatedRows = estimatedRows; }

    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }

    public boolean isUsesIndex() { return usesIndex; }
    public void setUsesIndex(boolean usesIndex) { this.usesIndex = usesIndex; }

    public boolean isHasFullTableScan() { return hasFullTableScan; }
    public void setHasFullTableScan(boolean hasFullTableScan) { this.hasFullTableScan = hasFullTableScan; }

    public boolean isUsesFilesort() { return usesFilesort; }
    public void setUsesFilesort(boolean usesFilesort) { this.usesFilesort = usesFilesort; }

    public boolean isUsesTemporaryTable() { return usesTemporaryTable; }
    public void setUsesTemporaryTable(boolean usesTemporaryTable) { this.usesTemporaryTable = usesTemporaryTable; }

    public String getKeyUsed() { return keyUsed; }
    public void setKeyUsed(String keyUsed) { this.keyUsed = keyUsed; }

    public List<TableAnalysis> getTables() { return tables; }
    public void setTables(List<TableAnalysis> tables) {
        this.tables = tables != null ? tables : new ArrayList<>();
    }
    public void addTable(TableAnalysis table) { this.tables.add(table); }

    /**
     * The tables EXPLAIN reported a full scan on, in plan order.
     *
     * WHY a derived getter rather than a stored field: hasFullTableScan says
     * THAT the plan contains a scan, which was never enough to name a table
     * in a CREATE INDEX suggestion. This names them without any caller having
     * to walk `tables` itself, and cannot drift out of sync with it.
     * Serialised as `fullScanTables`; read-only, so there is no setter.
     */
    public List<String> getFullScanTables() {
        List<String> names = new ArrayList<>();
        for (TableAnalysis table : tables) {
            if (table.isFullTableScan() && table.getTableName() != null) {
                names.add(table.getTableName());
            }
        }
        return names;
    }

    public boolean isHasUnindexedFilter() { return hasUnindexedFilter; }
    public void setHasUnindexedFilter(boolean hasUnindexedFilter) { this.hasUnindexedFilter = hasUnindexedFilter; }

    public boolean isHasUnanchoredRegex() { return hasUnanchoredRegex; }
    public void setHasUnanchoredRegex(boolean hasUnanchoredRegex) { this.hasUnanchoredRegex = hasUnanchoredRegex; }

    public String getFilteredColumn() { return filteredColumn; }
    public void setFilteredColumn(String filteredColumn) { this.filteredColumn = filteredColumn; }

    public String getOrderByColumn() { return orderByColumn; }
    public void setOrderByColumn(String orderByColumn) { this.orderByColumn = orderByColumn; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
    public void addWarning(String warning) { this.warnings.add(warning); }

    public boolean isExplainFailed() { return explainFailed; }
    public void setExplainFailed(boolean explainFailed) { this.explainFailed = explainFailed; }
}