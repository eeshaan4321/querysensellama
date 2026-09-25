package com.querysense.backend.dto;

import com.querysense.backend.optimization.RewriteChange;

import java.util.List;

/** Response body for the comparison endpoint: both plans, the delta, and why it changed. */
public class CompareResponse {

    private String originalQuery;
    private String optimizedQuery;
    private boolean rewritten;
    private List<RewriteChange> changes = List.of();

    private QueryAnalysisResult originalAnalysis;
    private QueryAnalysisResult optimizedAnalysis;

    private Long estimatedRowsDelta;
    private Double queryCostDelta;
    private boolean estimate = true;
    private String summary;

    public CompareResponse() {}

    public String getOriginalQuery() { return originalQuery; }
    public void setOriginalQuery(String originalQuery) { this.originalQuery = originalQuery; }

    public String getOptimizedQuery() { return optimizedQuery; }
    public void setOptimizedQuery(String optimizedQuery) { this.optimizedQuery = optimizedQuery; }

    public boolean isRewritten() { return rewritten; }
    public void setRewritten(boolean rewritten) { this.rewritten = rewritten; }

    public List<RewriteChange> getChanges() { return changes; }
    public void setChanges(List<RewriteChange> changes) { this.changes = changes; }

    public QueryAnalysisResult getOriginalAnalysis() { return originalAnalysis; }
    public void setOriginalAnalysis(QueryAnalysisResult originalAnalysis) { this.originalAnalysis = originalAnalysis; }

    public QueryAnalysisResult getOptimizedAnalysis() { return optimizedAnalysis; }
    public void setOptimizedAnalysis(QueryAnalysisResult optimizedAnalysis) { this.optimizedAnalysis = optimizedAnalysis; }

    public Long getEstimatedRowsDelta() { return estimatedRowsDelta; }
    public void setEstimatedRowsDelta(Long estimatedRowsDelta) { this.estimatedRowsDelta = estimatedRowsDelta; }

    public Double getQueryCostDelta() { return queryCostDelta; }
    public void setQueryCostDelta(Double queryCostDelta) { this.queryCostDelta = queryCostDelta; }

    public boolean isEstimate() { return estimate; }
    public void setEstimate(boolean estimate) { this.estimate = estimate; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
