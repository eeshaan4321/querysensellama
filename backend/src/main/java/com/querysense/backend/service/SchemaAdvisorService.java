package com.querysense.backend.service;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;

import java.util.List;

/** Recommends schema changes from a query analysis. */
public interface SchemaAdvisorService {

    List<SchemaRecommendation> recommend(QueryAnalysisResult result);
}
