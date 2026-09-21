

package com.querysense.backend.dto;

import java.util.ArrayList;
import java.util.List;

/** Response body for query analysis: the analysis and its optimisation suggestions. */
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