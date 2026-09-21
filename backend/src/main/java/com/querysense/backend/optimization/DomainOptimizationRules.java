package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;

import java.util.List;

/** A rule set run against a query analysis result. */
public interface DomainOptimizationRules {

    List<OptimizationSuggestion> evaluate(QueryAnalysisResult result);
}