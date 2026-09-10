package com.querysense.backend.service;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;

import java.util.List;

/**
 * SchemaAdvisorService
 *
 * WHY AN INTERFACE: same reasoning as QueryAnalysisService — the
 * Comparison Engine (Task C) will need to run the Schema Advisor on both
 * the original and optimized query's QueryAnalysisResult, and should
 * depend on this interface rather than SchemaAdvisorServiceImpl directly,
 * in case the rule logic changes shape later (e.g. Eeshaan's
 * Domain-Sensitive module ends up wanting to filter/reweight
 * recommendations by domain profile).
 */
public interface SchemaAdvisorService {

    /**
     * Applies rule-based schema/index recommendations against an already
     * analyzed query.
     *
     * @param result a populated QueryAnalysisResult (from QueryAnalysisService)
     * @return a list of recommendations; empty (never null) if nothing is wrong
     */
    List<SchemaRecommendation> recommend(QueryAnalysisResult result);
}
