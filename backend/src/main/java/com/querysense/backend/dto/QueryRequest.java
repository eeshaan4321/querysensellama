package com.querysense.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for query generation. */
public class QueryRequest {

    @NotBlank(message = "Query must not be blank")
    @Size(min = 3, max = 500, message = "Query must be between 3 and 500 characters")
    private String query;

    @NotBlank(message = "Database type must not be blank")
    private String databaseType;

    public QueryRequest() {}

    public QueryRequest(String query, String databaseType) {
        this.query = query;
        this.databaseType = databaseType;
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }
}