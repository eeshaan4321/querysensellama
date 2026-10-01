package com.querysense.backend.analysis;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.dto.QueryAnalysisResult;

/** Analyses one dialect's query into plan figures, warnings and a verdict. */
public interface PlanAnalyzer extends DialectAware {
    QueryAnalysisResult analyze(String query);
}
