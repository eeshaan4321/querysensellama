package com.querysense.backend.service.impl;

import com.querysense.backend.analysis.MongoSimpleAnalyzer;
import com.querysense.backend.analysis.MySqlExplainAnalyzer;
import com.querysense.backend.analysis.SqlPreflightValidator;
import com.querysense.backend.analysis.SqlStructureParser;
import com.querysense.backend.analysis.VerdictService;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.service.QueryAnalysisService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Combines structural parsing with EXPLAIN or MongoDB checks. */
@Service
public class QueryAnalysisServiceImpl implements QueryAnalysisService {

    private final SqlStructureParser sqlStructureParser;
    private final MySqlExplainAnalyzer mySqlExplainAnalyzer;
    private final MongoSimpleAnalyzer mongoSimpleAnalyzer;
    private final SqlPreflightValidator sqlPreflightValidator;
    private final VerdictService verdictService;

    public QueryAnalysisServiceImpl(SqlStructureParser sqlStructureParser,
                                    MySqlExplainAnalyzer mySqlExplainAnalyzer,
                                    MongoSimpleAnalyzer mongoSimpleAnalyzer,
                                    SqlPreflightValidator sqlPreflightValidator,
                                    VerdictService verdictService) {
        this.sqlStructureParser = sqlStructureParser;
        this.mySqlExplainAnalyzer = mySqlExplainAnalyzer;
        this.mongoSimpleAnalyzer = mongoSimpleAnalyzer;
        this.sqlPreflightValidator = sqlPreflightValidator;
        this.verdictService = verdictService;
    }

    @Override
    public QueryAnalysisResult analyze(String sqlOrMongoQuery, String databaseType) {
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return mongoSimpleAnalyzer.analyze(sqlOrMongoQuery, null);
        }

        QueryAnalysisResult structuralResult = sqlStructureParser.parse(sqlOrMongoQuery);

        if (!"SELECT".equals(structuralResult.getQueryType())
                || structuralResult.getTableName() == null) {
            return structuralResult;
        }

        Optional<String> preflightFailure =
                sqlPreflightValidator.validate(sqlOrMongoQuery, structuralResult.getTableName());
        if (preflightFailure.isPresent()) {
            structuralResult.addWarning(preflightFailure.get());
            structuralResult.setExplainFailed(true);
            verdictService.apply(structuralResult);
            return structuralResult;
        }

        QueryAnalysisResult explainResult = mySqlExplainAnalyzer.analyze(
                sqlOrMongoQuery, structuralResult.getTableName());

        QueryAnalysisResult merged = mergeResults(structuralResult, explainResult);

        verdictService.apply(merged);
        return merged;
    }

    private QueryAnalysisResult mergeResults(QueryAnalysisResult structural,
                                             QueryAnalysisResult explain) {
        explain.getWarnings().addAll(0, structural.getWarnings());
        explain.setFilteredColumn(structural.getFilteredColumn());
        explain.setOrderByColumn(structural.getOrderByColumn());
        return explain;
    }
}