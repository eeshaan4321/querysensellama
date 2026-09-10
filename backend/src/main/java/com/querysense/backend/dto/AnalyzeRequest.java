package com.querysense.backend.dto;

import com.querysense.backend.enums.DomainProfile;
import com.querysense.backend.enums.ResourceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AnalyzeRequest
 *
 * Mirrors the validation approach already used in QueryRequest
 * (NotBlank on the query text) so the two endpoints behave consistently
 * from the frontend's perspective.
 *
 * UPDATED: added domainProfile and resourceLevel. Without these, the
 * Domain-Sensitive Optimization Module and Resource-Conscious Decision
 * System have no way to know which rule set / threshold tier to apply —
 * the original shape only carried query + databaseType, which is enough
 * for Anant's analysis engine but not enough to drive Eeshaan's modules.
 * @NotNull (rather than NotBlank) is used here since these are enums, not
 * strings — a missing value deserializes to null, not an empty string.
 */
public class AnalyzeRequest {

    @NotBlank(message = "query must not be blank")
    private String query;

    @NotBlank(message = "databaseType must not be blank")
    private String databaseType; // "MySQL" or "MongoDB"

    @NotNull(message = "domainProfile must not be null")
    private DomainProfile domainProfile;

    @NotNull(message = "resourceLevel must not be null")
    private ResourceLevel resourceLevel;

    public AnalyzeRequest() {}

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }

    public DomainProfile getDomainProfile() { return domainProfile; }
    public void setDomainProfile(DomainProfile domainProfile) { this.domainProfile = domainProfile; }

    public ResourceLevel getResourceLevel() { return resourceLevel; }
    public void setResourceLevel(ResourceLevel resourceLevel) { this.resourceLevel = resourceLevel; }
}