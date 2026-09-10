package com.querysense.backend.dto;

/**
 * SchemaRecommendation
 *
 * WHY THIS CLASS EXISTS:
 * This is the Schema Advisor's half of the shared-contract pattern already
 * established by QueryAnalysisResult — SchemaAdvisorService reads a
 * QueryAnalysisResult and produces a list of these. Keeping it a plain DTO
 * (not a JPA @Entity) mirrors QueryAnalysisResult's own choice: persistence
 * for the SCHEMA_RECOMMENDATION ER entity is a separate concern (History
 * module territory) that hasn't been wired up yet, and this class shouldn't
 * block on that decision.
 *
 * FIELD NOTES:
 * - targetColumn is nullable: a STRUCTURAL recommendation (e.g. "review
 *   your GROUP BY/DISTINCT usage") isn't about one column, and even an
 *   INDEX recommendation can't always name a column (see
 *   QueryAnalysisResult.filteredColumn's own nullability notes).
 * - reason must reference the actual QueryAnalysisResult signal that fired
 *   it (e.g. "hasFullTableScan was true and no index was used"), not a
 *   generic template sentence — same "warnings are human-readable, not
 *   codes" principle QueryAnalysisResult already follows.
 * - This shape has NOT been reconciled against the SCHEMA_RECOMMENDATION
 *   entity in the ER diagram (CLAUDE.md Section 6 flags this as an
 *   existing gap — no column-level field list for that entity could be
 *   found in the repo). Treat this as provisional until checked against
 *   the actual diagram export.
 */
public class SchemaRecommendation {

    private String type;           // "INDEX", "NORMALIZATION", "STRUCTURAL"
    private String targetTable;
    private String targetColumn;   // nullable
    private String reason;
    private String suggestedAction;

    public SchemaRecommendation() {}

    public SchemaRecommendation(String type, String targetTable, String targetColumn,
                                 String reason, String suggestedAction) {
        this.type = type;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.reason = reason;
        this.suggestedAction = suggestedAction;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
}
