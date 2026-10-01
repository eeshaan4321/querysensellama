package com.querysense.backend.service.impl;

import com.querysense.backend.advisor.IndexAdvisor;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.SchemaAdvisorService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Schema recommendations derived from analysis signals, by the advisor of the query's dialect. */
@Service
public class SchemaAdvisorServiceImpl implements SchemaAdvisorService {

    private final DialectRegistry<IndexAdvisor> advisors;

    public SchemaAdvisorServiceImpl(List<IndexAdvisor> advisors) {
        this.advisors = new DialectRegistry<>(advisors, "index advisor");
    }

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        String type = result.getDatabaseType();
        Dialect dialect = type == null || type.isBlank() ? Dialect.MYSQL : Dialect.from(type);
        return advisors.get(dialect).recommend(result);
    }
}
