package com.querysense.backend.dto;

/** Liveness response: enough for a caller to branch on a field rather than parse a string. */
public class HealthResponse {

    private String status;
    private String schemaLabel;
    private boolean builtInSchema;
    private String mongo;

    public HealthResponse() {}

    public HealthResponse(String status, String schemaLabel, boolean builtInSchema) {
        this.status = status;
        this.schemaLabel = schemaLabel;
        this.builtInSchema = builtInSchema;
    }

    public HealthResponse(String status, String schemaLabel, boolean builtInSchema, String mongo) {
        this(status, schemaLabel, builtInSchema);
        this.mongo = mongo;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSchemaLabel() { return schemaLabel; }
    public void setSchemaLabel(String schemaLabel) { this.schemaLabel = schemaLabel; }

    public boolean isBuiltInSchema() { return builtInSchema; }
    public void setBuiltInSchema(boolean builtInSchema) { this.builtInSchema = builtInSchema; }

    public String getMongo() { return mongo; }
    public void setMongo(String mongo) { this.mongo = mongo; }
}
