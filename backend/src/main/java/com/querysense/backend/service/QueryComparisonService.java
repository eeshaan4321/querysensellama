package com.querysense.backend.service;

import com.querysense.backend.dto.CompareResponse;

/** Compares a query against its rewritten form. */
public interface QueryComparisonService {
    default CompareResponse compare(String query, String databaseType) {
        return compare(query, databaseType, null);
    }

    CompareResponse compare(String query, String databaseType, String userInput);
}
