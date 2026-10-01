package com.querysense.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for the comparison endpoint. */
public class CompareRequest {

    @NotBlank(message = "query must not be blank")
    private String query;

    @NotBlank(message = "databaseType must not be blank")
    private String databaseType;

    private String userInput;

    public CompareRequest() {}

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public String getUserInput() { return userInput; }
    public void setUserInput(String userInput) { this.userInput = userInput; }
}
