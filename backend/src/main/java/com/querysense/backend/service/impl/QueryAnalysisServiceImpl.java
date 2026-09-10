package com.querysense.backend.service.impl;

import com.querysense.backend.analysis.MongoSimpleAnalyzer;
import com.querysense.backend.analysis.MySqlExplainAnalyzer;
import com.querysense.backend.analysis.SqlPreflightValidator;
import com.querysense.backend.analysis.SqlStructureParser;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.service.QueryAnalysisService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * QueryAnalysisServiceImpl
 *
 * WHY THIS CLASS EXISTS:
 * SqlStructureParser answers "what does this query look like" and
 * MySqlExplainAnalyzer answers "how does MySQL actually plan to run it" —
 * two separate concerns, tested separately (see the manual EXPLAIN
 * verification with idx_city). This class is the glue: it runs structure
 * parsing first to get the table name automatically (replacing the manual
 * `table=customers` parameter from the throwaway test controller), then
 * feeds that into the EXPLAIN analyzer, and merges both results into one
 * QueryAnalysisResult.
 *
 * WHY WE ONLY RUN EXPLAIN FOR SELECT:
 * Matches the safety note already documented in MySqlExplainAnalyzer —
 * EXPLAIN behaves differently and is less meaningful for DELETE/other
 * statement types for this project's scope. If structure parsing finds a
 * non-SELECT MySQL query, we return the structural result alone.
 *
 * MONGODB SCOPE NOTE:
 * MongoSimpleAnalyzer (Task 4) does lightweight, pattern-based checks
 * since there's no real MongoDB client/query object yet — see that
 * class's doc for details. "collectionName" is passed as null here
 * since we don't have a structural parser for Mongo query text (that
 * would be a much larger task, out of MVP scope per the synopsis).
 */
@Service
public class QueryAnalysisServiceImpl implements QueryAnalysisService {

    private final SqlStructureParser sqlStructureParser;
    private final MySqlExplainAnalyzer mySqlExplainAnalyzer;
    private final MongoSimpleAnalyzer mongoSimpleAnalyzer;
    private final SqlPreflightValidator sqlPreflightValidator;

    public QueryAnalysisServiceImpl(SqlStructureParser sqlStructureParser,
                                    MySqlExplainAnalyzer mySqlExplainAnalyzer,
                                    MongoSimpleAnalyzer mongoSimpleAnalyzer,
                                    SqlPreflightValidator sqlPreflightValidator) {
        this.sqlStructureParser = sqlStructureParser;
        this.mySqlExplainAnalyzer = mySqlExplainAnalyzer;
        this.mongoSimpleAnalyzer = mongoSimpleAnalyzer;
        this.sqlPreflightValidator = sqlPreflightValidator;
    }

    @Override
    public QueryAnalysisResult analyze(String sqlOrMongoQuery, String databaseType) {
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return mongoSimpleAnalyzer.analyze(sqlOrMongoQuery, null);
        }

        // Step 1: structural parsing — gets us tableName/queryType automatically,
        // which used to require a human typing it into a URL param.
        QueryAnalysisResult structuralResult = sqlStructureParser.parse(sqlOrMongoQuery);

        // Only SELECT queries get the full EXPLAIN treatment — see class doc.
        if (!"SELECT".equals(structuralResult.getQueryType())
                || structuralResult.getTableName() == null) {
            return structuralResult;
        }

        // Step 1.5: preflight — catch the failure modes we can detect from the
        // SQL text alone (ONLY_FULL_GROUP_BY violations, hallucinated columns)
        // before ever sending the query to MySQL. WHY here and not inside
        // MySqlExplainAnalyzer: this lets us fail fast with a specific message
        // and skip the round trip entirely, while MySqlExplainAnalyzer's own
        // catch block remains the safety net for anything this doesn't catch.
        Optional<String> preflightFailure =
                sqlPreflightValidator.validate(sqlOrMongoQuery, structuralResult.getTableName());
        if (preflightFailure.isPresent()) {
            structuralResult.addWarning(preflightFailure.get());
            structuralResult.setExplainFailed(true);
            return structuralResult;
        }

        // Step 2: run EXPLAIN using the table name we just extracted, then merge.
        QueryAnalysisResult explainResult = mySqlExplainAnalyzer.analyze(
                sqlOrMongoQuery, structuralResult.getTableName());

        return mergeResults(structuralResult, explainResult);
    }

    /**
     * Combines the structural findings (JOIN/WHERE/GROUP BY warnings) with
     * the EXPLAIN findings (full scan/index/filesort warnings) into one
     * result. WHY a manual merge instead of just returning explainResult:
     * explainResult doesn't carry the structural warnings from Task 2
     * (e.g. "no WHERE clause"), so we'd silently lose useful signals for
     * Eeshaan's and Vedant's modules if we just took one or the other.
     *
     * WHY filteredColumn/orderByColumn ARE COPIED HERE TOO: these are set
     * by SqlStructureParser (it has the AST), but MySqlExplainAnalyzer
     * never touches them — so without this explicit copy, the Schema
     * Advisor (which reads the merged/returned explain result) would
     * always see them as null, even though structural parsing found them.
     */
    private QueryAnalysisResult mergeResults(QueryAnalysisResult structural,
                                             QueryAnalysisResult explain) {
        // explainResult already carries tableName/queryType/databaseType set
        // identically, plus all the EXPLAIN-derived fields — start from it
        // and layer the structural warnings on top.
        explain.getWarnings().addAll(0, structural.getWarnings());
        explain.setFilteredColumn(structural.getFilteredColumn());
        explain.setOrderByColumn(structural.getOrderByColumn());
        return explain;
    }
}