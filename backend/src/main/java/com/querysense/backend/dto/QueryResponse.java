package com.querysense.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

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
}
