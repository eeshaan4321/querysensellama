package com.querysense.backend.dto;

import com.querysense.backend.querybuilder.Interpretation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Response body for query generation, including which layer handled it. */
@Getter
@Setter
@AllArgsConstructor
public class QueryResponse {

    private String generatedQuery;
    private String targetDb;
    private String handledBy;
    private Double confidence;

    private RuleTrace ruleTrace;

    private String schemaWarning;

    private List<Interpretation> interpretations = List.of();

    public QueryResponse(String generatedQuery, String targetDb, String handledBy, Double confidence,
                         RuleTrace ruleTrace, String schemaWarning) {
        this(generatedQuery, targetDb, handledBy, confidence, ruleTrace, schemaWarning, List.of());
    }
}
