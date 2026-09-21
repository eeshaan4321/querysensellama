package com.querysense.backend.service;

import com.querysense.backend.dto.QueryAnalysisResult;

/** Analyses a query's execution plan. */
public interface QueryAnalysisService {

    QueryAnalysisResult analyze(String sqlOrMongoQuery, String databaseType);
}