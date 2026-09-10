package com.querysense.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * QueryRequest — UPDATED with validation
 *
 * WHY VALIDATION ANNOTATIONS?
 * Never trust user input. If someone sends an empty query or a 10,000
 * character string, your rule engine and LLM prompt will behave badly.
 * These annotations enforce constraints BEFORE the request reaches the service.
 *
 * @NotBlank  — rejects null, empty string "", and whitespace-only "   "
 * @Size      — enforces min/max character length
 *
 * These only activate when @Valid is added to @RequestBody in the controller.
 * Without @Valid, these annotations are ignored — both are needed together.
 */
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