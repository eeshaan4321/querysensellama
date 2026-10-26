package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Analyses a MySQL query: its structure, the preflight checks, then EXPLAIN and the verdict. */
@Component
public class MySqlPlanAnalyzer implements PlanAnalyzer {

    private final SqlStructureParser sqlStructureParser;
    private final MySqlExplainAnalyzer mySqlExplainAnalyzer;
    private final SqlPreflightValidator sqlPreflightValidator;
    private final VerdictService verdictService;

    public MySqlPlanAnalyzer(SqlStructureParser sqlStructureParser,
                             MySqlExplainAnalyzer mySqlExplainAnalyzer,
                             SqlPreflightValidator sqlPreflightValidator,
                             VerdictService verdictService) {
        this.sqlStructureParser = sqlStructureParser;
        this.mySqlExplainAnalyzer = mySqlExplainAnalyzer;
        this.sqlPreflightValidator = sqlPreflightValidator;
        this.verdictService = verdictService;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public QueryAnalysisResult analyze(String sqlOrMongoQuery) {
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
        explain.setTableAliases(structural.getTableAliases());
        return explain;
    }
}
