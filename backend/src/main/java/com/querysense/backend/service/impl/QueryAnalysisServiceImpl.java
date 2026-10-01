package com.querysense.backend.service.impl;

import com.querysense.backend.analysis.PlanAnalyzer;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.service.QueryAnalysisService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Hands a query to the plan analyzer of its database type. */
@Service
public class QueryAnalysisServiceImpl implements QueryAnalysisService {

    private final DialectRegistry<PlanAnalyzer> analyzers;

    public QueryAnalysisServiceImpl(List<PlanAnalyzer> analyzers) {
        this.analyzers = new DialectRegistry<>(analyzers, "plan analyzer");
    }

    @Override
    public QueryAnalysisResult analyze(String sqlOrMongoQuery, String databaseType) {
        return analyzers.get(Dialect.from(databaseType)).analyze(sqlOrMongoQuery);
    }
}
