package com.querysense.backend.service;

import com.querysense.backend.dto.QueryResponse;

/** Generates a query from a natural-language question. */
public interface QueryGenerationService {

    QueryResponse generateQuery(String input, String databaseType);
}