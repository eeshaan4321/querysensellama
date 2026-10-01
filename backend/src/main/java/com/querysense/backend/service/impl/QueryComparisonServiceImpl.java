package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.optimization.QueryRewrite;
import com.querysense.backend.optimization.Rewriter;
import com.querysense.backend.service.QueryAnalysisService;
import com.querysense.backend.service.QueryComparisonService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Runs the analyser over a query and its rewrite, and reports the difference. */
@Service
public class QueryComparisonServiceImpl implements QueryComparisonService {

    private final DialectRegistry<Rewriter> rewriters;
    private final QueryAnalysisService queryAnalysisService;
    private final OptimizedQueryRecorder recorder;

    public QueryComparisonServiceImpl(List<Rewriter> rewriters,
                                      QueryAnalysisService queryAnalysisService,
                                      OptimizedQueryRecorder recorder) {
        this.rewriters = new DialectRegistry<>(rewriters, "query rewriter");
        this.queryAnalysisService = queryAnalysisService;
        this.recorder = recorder;
    }

    @Override
    public CompareResponse compare(String query, String databaseType, String userInput) {
        Dialect dialect = Dialect.from(databaseType);
        boolean mongo = dialect == Dialect.MONGODB;
        CompareResponse response = new CompareResponse();
        response.setOriginalQuery(query);
        response.setOptimizedQuery(query);
        response.setEstimate(!mongo);

        QueryRewrite rewrite = rewriters.get(dialect).rewrite(query);

        QueryAnalysisResult before = queryAnalysisService.analyze(query, databaseType);
        response.setOriginalAnalysis(before);

        if (!rewrite.changed()) {
            response.setSummary("No rewrite rule applied to this query, "
                    + "so there is nothing to compare it against.");
            return response;
        }

        QueryAnalysisResult after = queryAnalysisService.analyze(rewrite.sql(), databaseType);

        if (after.isExplainFailed()) {
            response.setSummary(mongo
                    ? "A rewrite was produced but could not be checked — MongoDB's explain rejected it, so it is "
                            + "withheld rather than offered as an improvement. This usually means the active "
                            + "schema and the live collections disagree about a field."
                    : "A rewrite was produced but could not be checked — EXPLAIN rejected it, "
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
        response.setSummary(mongo ? summariseMongo(response, before, after) : summarise(response));

        recorder.record(dialect, userInput, query, rewrite.sql());
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

    private String summariseMongo(CompareResponse response, QueryAnalysisResult before, QueryAnalysisResult after) {
        int ruleCount = response.getChanges().size();
        String opening = ruleCount + (ruleCount == 1 ? " rewrite rule applied. " : " rewrite rules applied. ");
        String planChange = before.getAccessType() != null && after.getAccessType() != null
                && !before.getAccessType().equals(after.getAccessType())
                ? " The plan changed from " + before.getAccessType() + " to " + after.getAccessType() + "."
                : "";

        Long delta = response.getEstimatedRowsDelta();
        if (delta == null) {
            return opening + "MongoDB reported no documents-examined figure for one of the two plans, "
                    + "so the two cannot be compared on documents." + planChange;
        }
        if (delta < 0) {
            return opening + "MongoDB examined " + Math.abs(delta) + " fewer documents under the rewrite, "
                    + "measured by running both plans with explain." + planChange;
        }
        if (delta > 0) {
            return opening + "MongoDB examined " + delta + " more documents under the rewrite — the rewrite did "
                    + "not help this query, measured by running both plans with explain." + planChange;
        }
        return opening + "MongoDB examined the same number of documents either way, so the rewrite changes "
                + "the shape of the query without changing what it reads." + planChange;
    }
}
