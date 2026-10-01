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
    private String suggestedChange;

    public OptimizationSuggestion() {}

    public OptimizationSuggestion(String message, SuggestionSeverity severity, Source source) {
        this(message, severity, source, null);
    }

    public OptimizationSuggestion(String message, SuggestionSeverity severity, Source source, String suggestedChange) {
        this.message = message;
        this.severity = severity;
        this.source = source;
        this.suggestedChange = suggestedChange;
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

    public static OptimizationSuggestion domain(String message, SuggestionSeverity severity, String suggestedChange) {
        return new OptimizationSuggestion(message, severity, Source.DOMAIN, suggestedChange);
    }

    public static OptimizationSuggestion resource(String message, SuggestionSeverity severity, String suggestedChange) {
        return new OptimizationSuggestion(message, severity, Source.RESOURCE, suggestedChange);
    }

    public OptimizationSuggestion withSeverity(SuggestionSeverity newSeverity) {
        return new OptimizationSuggestion(message, newSeverity, source, suggestedChange);
    }

    public String getSuggestedChange() { return suggestedChange; }
    public void setSuggestedChange(String suggestedChange) { this.suggestedChange = suggestedChange; }
}
