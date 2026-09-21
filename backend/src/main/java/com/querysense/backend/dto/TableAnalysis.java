package com.querysense.backend.dto;

/** EXPLAIN results for one table in a query plan. */
public class TableAnalysis {

    private String tableName;
    private String accessType;
    private String possibleKeys;
    private String keyUsed;
    private String keyLength;
    private String ref;
    private Long estimatedRows;
    private Double filtered;
    private String extra;

    private boolean fullTableScan;
    private boolean usesIndex;
    private boolean usesFilesort;
    private boolean usesTemporaryTable;

    public TableAnalysis() {}

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }

    public String getPossibleKeys() { return possibleKeys; }
    public void setPossibleKeys(String possibleKeys) { this.possibleKeys = possibleKeys; }

    public String getKeyUsed() { return keyUsed; }
    public void setKeyUsed(String keyUsed) { this.keyUsed = keyUsed; }

    public String getKeyLength() { return keyLength; }
    public void setKeyLength(String keyLength) { this.keyLength = keyLength; }

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }

    public Long getEstimatedRows() { return estimatedRows; }
    public void setEstimatedRows(Long estimatedRows) { this.estimatedRows = estimatedRows; }

    public Double getFiltered() { return filtered; }
    public void setFiltered(Double filtered) { this.filtered = filtered; }

    public Long getEstimatedRowsReturned() {
        if (estimatedRows == null || filtered == null) {
            return null;
        }
        return Math.round(estimatedRows * filtered / 100.0);
    }

    public Double getReadAmplification() {
        Long returned = getEstimatedRowsReturned();
        if (estimatedRows == null || returned == null) {
            return null;
        }
        if (returned == 0L) {
            return estimatedRows.doubleValue();
        }
        return estimatedRows.doubleValue() / returned.doubleValue();
    }

    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }

    public boolean isFullTableScan() { return fullTableScan; }
    public void setFullTableScan(boolean fullTableScan) { this.fullTableScan = fullTableScan; }

    public boolean isUsesIndex() { return usesIndex; }
    public void setUsesIndex(boolean usesIndex) { this.usesIndex = usesIndex; }

    public boolean isUsesFilesort() { return usesFilesort; }
    public void setUsesFilesort(boolean usesFilesort) { this.usesFilesort = usesFilesort; }

    public boolean isUsesTemporaryTable() { return usesTemporaryTable; }
    public void setUsesTemporaryTable(boolean usesTemporaryTable) { this.usesTemporaryTable = usesTemporaryTable; }

    @Override
    public String toString() {
        return "TableAnalysis{" + tableName + " type=" + accessType
                + " key=" + keyUsed + " rows=" + estimatedRows + "}";
    }
}
