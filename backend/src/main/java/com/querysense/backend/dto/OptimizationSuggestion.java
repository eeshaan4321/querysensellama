package com.querysense.backend.dto;

import com.querysense.backend.enums.SuggestionSeverity;

/**
 * OptimizationSuggestion
 *
 * The common output unit produced by both the Domain-Sensitive Optimization
 * Module and the Resource-Conscious Decision System. Both modules consume
 * the same QueryAnalysisResult (Anant's contract) and each produce a
 * List<OptimizationSuggestion>; QueryGenerationServiceImpl merges the
 * two lists before returning an AnalyzeResponse.
 *
 * FIELD NOTES:
 * - message should be a complete, human-readable sentence (same convention
 *   as QueryAnalysisResult.warnings) — the frontend may render this
 *   directly, with no further formatting.
 * - source records which module produced the suggestion, so the frontend
 *   and the eventual Comparison Engine can distinguish "this changed
 *   because of the domain profile" from "this changed because of resource
 *   level" without parsing the message text.
 */
public class OptimizationSuggestion {

    public enum Source {
        DOMAIN,
        RESOURCE
    }

    private String message;
    private SuggestionSeverity severity;
    private Source source;

    public OptimizationSuggestion() {}

    public OptimizationSuggestion(String message, SuggestionSeverity severity, Source source) {
        this.message = message;
        this.severity = severity;
        this.source = source;
    }

    // Convenience factories so rule classes don't repeat "Source.DOMAIN" / "Source.RESOURCE" everywhere
    public static OptimizationSuggestion domain(String message, SuggestionSeverity severity) {
        return new OptimizationSuggestion(message, severity, Source.DOMAIN);
    }

    public static OptimizationSuggestion resource(String message, SuggestionSeverity severity) {
        return new OptimizationSuggestion(message, severity, Source.RESOURCE);
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public SuggestionSeverity getSeverity() { return severity; }
    public void setSeverity(SuggestionSeverity severity) { this.severity = severity; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
}