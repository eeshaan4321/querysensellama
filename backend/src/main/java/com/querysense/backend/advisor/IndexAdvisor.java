package com.querysense.backend.advisor;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;

import java.util.List;

/** Schema recommendations for one dialect, read from a query analysis. */
public interface IndexAdvisor extends DialectAware {
    List<SchemaRecommendation> recommend(QueryAnalysisResult result);
}
