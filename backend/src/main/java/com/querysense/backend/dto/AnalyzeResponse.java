

package com.querysense.backend.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * AnalyzeResponse
 *
 * The response body for /api/query/analyze. Wraps Anant's
 * QueryAnalysisResult together with the merged suggestions from the
 * Domain-Sensitive Optimization Module and the Resource-Conscious Decision
 * System, so the frontend gets the raw analysis and the actionable advice
 * in a single response instead of two round trips.
 *
 * There was no response DTO for this endpoint before this class —
 * QueryResponse only carries generatedQuery, which belongs to
 * /api/query/generate, not /analyze.
 */
public class AnalyzeResponse {

    private QueryAnalysisResult analysis;
    private List<OptimizationSuggestion> suggestions = new ArrayList<>();

    public AnalyzeResponse() {}

    public AnalyzeResponse(QueryAnalysisResult analysis, List<OptimizationSuggestion> suggestions) {
        this.analysis = analysis;
        this.suggestions = suggestions;
    }

    public QueryAnalysisResult getAnalysis() { return analysis; }
    public void setAnalysis(QueryAnalysisResult analysis) { this.analysis = analysis; }

    public List<OptimizationSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<OptimizationSuggestion> suggestions) { this.suggestions = suggestions; }
    public void addSuggestion(OptimizationSuggestion suggestion) { this.suggestions.add(suggestion); }
}