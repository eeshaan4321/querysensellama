package com.querysense.backend.dto;

import java.util.ArrayList;
import java.util.List;

/** Result of analysing a query: plan metrics, flags and warnings. */
public class QueryAnalysisResult {

    private String tableName;
    private String queryType;
    private String databaseType;

    private Long estimatedRows;
    private Double filtered;
    private Long estimatedRowsReturned;
    private String accessType;
    private boolean usesIndex;
    private boolean hasFullTableScan;
    private boolean usesFilesort;
    private boolean usesTemporaryTable;
    private String keyUsed;

    private List<TableAnalysis> tables = new ArrayList<>();

    private boolean hasUnindexedFilter;
    private boolean hasUnanchoredRegex;

    private String filteredColumn;
    private String orderByColumn;

    private List<String> warnings = new ArrayList<>();

    private boolean explainFailed;

    private PlanNode planTree;

    private Double queryCost;

    private AnalysisVerdict verdict;

    public QueryAnalysisResult() {}

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public Long getEstimatedRows() { return estimatedRows; }
    public void setEstimatedRows(Long estimatedRows) { this.estimatedRows = estimatedRows; }

    public Double getFiltered() { return filtered; }
    public void setFiltered(Double filtered) { this.filtered = filtered; }

    public Long getEstimatedRowsReturned() { return estimatedRowsReturned; }
    public void setEstimatedRowsReturned(Long estimatedRowsReturned) {
        this.estimatedRowsReturned = estimatedRowsReturned;
    }

    public Double getReadAmplification() {
        if (estimatedRows == null || estimatedRowsReturned == null) {
            return null;
        }
        if (estimatedRowsReturned == 0L) {
            return estimatedRows.doubleValue();
        }
        return estimatedRows.doubleValue() / estimatedRowsReturned.doubleValue();
    }

    public Double getQueryCost() { return queryCost; }
    public void setQueryCost(Double queryCost) { this.queryCost = queryCost; }

    public AnalysisVerdict getVerdict() { return verdict; }
    public void setVerdict(AnalysisVerdict verdict) { this.verdict = verdict; }

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

    public PlanNode getPlanTree() { return planTree; }
    public void setPlanTree(PlanNode planTree) { this.planTree = planTree; }

    public boolean isExplainFailed() { return explainFailed; }
    public void setExplainFailed(boolean explainFailed) { this.explainFailed = explainFailed; }
}