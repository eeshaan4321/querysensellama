package com.querysense.backend.enums;

/**
 * SuggestionSeverity
 *
 * How urgently a single OptimizationSuggestion should be surfaced to the
 * user. This is distinct from ResourceLevel — ResourceLevel describes the
 * system's available resources (an input), while SuggestionSeverity
 * describes how serious a given finding is (an output). The
 * Resource-Conscious Decision System uses ResourceLevel to help decide
 * which SuggestionSeverity to assign to a given finding — e.g. a full
 * table scan is CRITICAL under LOW resources but only WARNING under HIGH
 * resources.
 */
public enum SuggestionSeverity {
    INFO,
    WARNING,
    CRITICAL
}