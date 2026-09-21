package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.springframework.stereotype.Service;

import java.util.List;

/** Runs the optimisation rule set against an analysis result. */
@Service
public class DomainOptimizationService {

    private final DomainOptimizationRules rules = new TransactionalOptimizationRules();

    public List<OptimizationSuggestion> evaluate(QueryAnalysisResult result) {
        if (result == null) {
            throw new IllegalArgumentException("QueryAnalysisResult must not be null");
        }
        return rules.evaluate(result);
    }
}