package com.querysense.backend.service;

import com.querysense.backend.dto.CompareResponse;

/** Compares a query against its rewritten form. */
public interface QueryComparisonService {
    CompareResponse compare(String query, String databaseType);
}
