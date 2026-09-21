package com.querysense.backend.dto;

/** A schema recommendation with its reason and suggested action. */
public class SchemaRecommendation {

    private String type;
    private String targetTable;
    private String targetColumn;

    private String summary;

    private String reason;
    private String suggestedAction;

    public SchemaRecommendation() {}

    public SchemaRecommendation(String type, String targetTable, String targetColumn,
                                 String summary, String reason, String suggestedAction) {
        this.type = type;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.summary = summary;
        this.reason = reason;
        this.suggestedAction = suggestedAction;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
}
