package com.querysense.backend.dto;

import com.querysense.backend.enums.SuggestionSeverity;

/** A suggestion from the domain or resource optimisation module. */
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