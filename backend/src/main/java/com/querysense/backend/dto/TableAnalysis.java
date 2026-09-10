package com.querysense.backend.dto;

/**
 * TableAnalysis
 *
 * WHY THIS CLASS EXISTS:
 * MySQL's EXPLAIN emits one row per table it touches. The original analyzer
 * folded every row into a single flat QueryAnalysisResult, so a two-table
 * JOIN lost the very fact that makes it worth analyzing: WHICH table was
 * scanned and which one used an index. The second row simply overwrote the
 * first row's accessType/keyUsed.
 *
 * This class is one EXPLAIN row, kept whole. QueryAnalysisResult now carries
 * a list of these alongside its existing query-level summary fields, so
 *
 *     customers -> ALL -> no index
 *     orders    -> ref -> idx_customer_id
 *
 * stays two distinct findings instead of collapsing into one.
 *
 * FIELD NOTES:
 * - tableName comes from EXPLAIN's own `table` column, not from
 *   SqlStructureParser. For a derived table or a UNION result MySQL puts a
 *   placeholder such as "&lt;derived2&gt;" there; that is reported verbatim
 *   rather than guessed at.
 * - possibleKeys / ref are MySQL comma-separated lists, kept as the raw
 *   string. They are informational: they tell the reader which indexes were
 *   *candidates*, which is what distinguishes "no index exists" from "an
 *   index exists but the optimiser did not choose it".
 * - estimatedRows is nullable. EXPLAIN reports NULL for rows on plans such as
 *   "Impossible WHERE" or "Select tables optimized away", and 0 is a
 *   different claim from "not reported".
 * - extra is EXPLAIN's `Extra` column verbatim; usesFilesort and
 *   usesTemporaryTable are the parsed form of the two flags this project
 *   acts on. Keeping both means a future rule can read a flag we don't
 *   parse yet without another schema change.
 */
public class TableAnalysis {

    private String tableName;
    private String accessType;      // ALL, ref, eq_ref, range, index, const...
    private String possibleKeys;    // indexes MySQL considered; null if none
    private String keyUsed;         // index MySQL actually chose; null if none
    private String keyLength;       // key_len, as reported (a string in EXPLAIN)
    private String ref;             // what the index was compared against
    private Long estimatedRows;     // nullable — see class doc
    private String extra;           // EXPLAIN's Extra column, verbatim

    private boolean fullTableScan;      // accessType == ALL
    private boolean usesIndex;          // keyUsed != null
    private boolean usesFilesort;       // Extra contains "Using filesort"
    private boolean usesTemporaryTable; // Extra contains "Using temporary"

    public TableAnalysis() {}

    // -----------------------------------------------------------------
    // Getters and setters
    // -----------------------------------------------------------------

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
