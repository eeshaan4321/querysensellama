package com.querysense.backend.dto;

/** Severity, label and plain-English headline for a query analysis. */
public class AnalysisVerdict {

    public enum Severity { UNKNOWN, INFO, WARNING, CRITICAL }

    private Severity severity;
    private String label;
    private String headline;
    private String detail;

    public AnalysisVerdict() {}

    public AnalysisVerdict(Severity severity, String label, String headline, String detail) {
        this.severity = severity;
        this.label = label;
        this.headline = headline;
        this.detail = detail;
    }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    @Override
    public String toString() {
        return "AnalysisVerdict{" + severity + ": " + headline + "}";
    }
}
