package com.querysense.backend.service;

import com.querysense.backend.dto.QueryAnalysisResult;

/**
 * QueryAnalysisService
 *
 * WHY AN INTERFACE (not just a concrete class):
 * Same pattern as QueryGenerationService/QueryGenerationServiceImpl already
 * in the codebase. Eeshaan's Domain-Sensitive Optimization Module and
 * Vedant's Schema Advisor should depend on THIS interface, not on the
 * concrete Impl or on SqlStructureParser/MySqlExplainAnalyzer directly —
 * that way, if the analysis internals change later (e.g. Anant adds
 * multi-table JOIN analysis), nothing downstream needs to change.
 */
public interface QueryAnalysisService {

    /**
     * Analyzes a query end-to-end: parses its structure and, for MySQL
     * SELECT queries, runs EXPLAIN against the live database.
     *
     * @param sqlOrMongoQuery the generated query string (from QueryGenerationService)
     * @param databaseType    "MySQL" or "MongoDB"
     * @return a fully populated QueryAnalysisResult
     */
    QueryAnalysisResult analyze(String sqlOrMongoQuery, String databaseType);
}