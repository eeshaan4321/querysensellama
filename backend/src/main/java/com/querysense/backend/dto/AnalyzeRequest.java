package com.querysense.backend.dto;

import com.querysense.backend.enums.ResourceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request body for the analysis and schema-advisor endpoints. */
public class AnalyzeRequest {

    @NotBlank(message = "query must not be blank")
    private String query;

    @NotBlank(message = "databaseType must not be blank")
    private String databaseType;

    @NotNull(message = "resourceLevel must not be null")
    private ResourceLevel resourceLevel;

    public AnalyzeRequest() {}

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public ResourceLevel getResourceLevel() { return resourceLevel; }
    public void setResourceLevel(ResourceLevel resourceLevel) { this.resourceLevel = resourceLevel; }
}