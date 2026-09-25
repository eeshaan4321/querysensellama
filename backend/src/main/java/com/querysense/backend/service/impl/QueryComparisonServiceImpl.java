package com.querysense.backend.service.impl;

import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.optimization.QueryRewrite;
import com.querysense.backend.optimization.QueryRewriter;
import com.querysense.backend.service.QueryAnalysisService;
import com.querysense.backend.service.QueryComparisonService;
import org.springframework.stereotype.Service;

/** Runs the analyser over a query and its rewrite, and reports the difference. */
@Service
public class QueryComparisonServiceImpl implements QueryComparisonService {

    private final QueryRewriter queryRewriter;
    private final QueryAnalysisService queryAnalysisService;

    public QueryComparisonServiceImpl(QueryRewriter queryRewriter,
                                      QueryAnalysisService queryAnalysisService) {
        this.queryRewriter = queryRewriter;
        this.queryAnalysisService = queryAnalysisService;
    }

    @Override
    public CompareResponse compare(String query, String databaseType) {
        CompareResponse response = new CompareResponse();
        response.setOriginalQuery(query);
        response.setOptimizedQuery(query);

        if (!"MySQL".equalsIgnoreCase(databaseType)) {
            response.setSummary("Comparison is SQL-only for now. "
                    + "The rewriter has no MongoDB rules, so no MongoDB query is changed here.");
            return response;
        }

        QueryRewrite rewrite = queryRewriter.rewrite(query, databaseType);

        QueryAnalysisResult before = queryAnalysisService.analyze(query, databaseType);
        response.setOriginalAnalysis(before);

        if (!rewrite.changed()) {
            response.setSummary("No rewrite rule applied to this query, "
                    + "so there is nothing to compare it against.");
            return response;
        }

        QueryAnalysisResult after = queryAnalysisService.analyze(rewrite.sql(), databaseType);

        // SQL the database will not even plan is not an optimisation, whatever the rules intended.
        if (after.isExplainFailed()) {
            response.setSummary("A rewrite was produced but could not be checked — EXPLAIN rejected it, "
                    + "so it is withheld rather than offered as an improvement. "
                    + "This usually means the active schema and the live tables disagree about a column.");
            return response;
        }

        response.setOptimizedQuery(rewrite.sql());
        response.setRewritten(true);
        response.setChanges(rewrite.changes());
        response.setOptimizedAnalysis(after);
        response.setEstimatedRowsDelta(deltaOf(before.getEstimatedRows(), after.getEstimatedRows()));
        response.setQueryCostDelta(deltaOf(before.getQueryCost(), after.getQueryCost()));
        response.setSummary(summarise(response));

        return response;
    }

    private Long deltaOf(Long before, Long after) {
        return before == null || after == null ? null : after - before;
    }

    private Double deltaOf(Double before, Double after) {
        return before == null || after == null ? null : after - before;
    }

    /** Every number here comes from EXPLAIN, which estimates — so the wording never claims measurement. */
    private String summarise(CompareResponse response) {
        int ruleCount = response.getChanges().size();
        String opening = ruleCount + (ruleCount == 1 ? " rewrite rule applied. " : " rewrite rules applied. ");

        Long rowDelta = response.getEstimatedRowsDelta();
        if (rowDelta == null) {
            return opening + "EXPLAIN reported no row estimate for one of the two plans, "
                    + "so the two cannot be compared on rows.";
        }
        if (rowDelta < 0) {
            return opening + "EXPLAIN estimates " + Math.abs(rowDelta)
                    + " fewer rows read under the rewrite. That is an estimate from the plan, not a measurement.";
        }
        if (rowDelta > 0) {
            return opening + "EXPLAIN estimates " + rowDelta
                    + " more rows read under the rewrite — the rewrite did not help this query. "
                    + "That is an estimate from the plan, not a measurement.";
        }
        return opening + "EXPLAIN estimates the same number of rows read either way, "
                + "so the rewrite changes the shape of the query without changing what it reads.";
    }
}
