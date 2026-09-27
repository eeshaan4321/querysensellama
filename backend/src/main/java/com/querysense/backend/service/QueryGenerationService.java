package com.querysense.backend.service;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.enums.ResourceLevel;

/** Generates a query from a natural-language question. */
public interface QueryGenerationService {

    QueryResponse generateQuery(String input, String databaseType, ResourceLevel resourceLevel);

    default QueryResponse generateQuery(String input, String databaseType) {
        return generateQuery(input, databaseType, null);
    }
}
