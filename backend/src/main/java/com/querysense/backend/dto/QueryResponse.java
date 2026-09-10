package com.querysense.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * QueryResponse — UPDATED
 *
 * Previously carried only generatedQuery, which meant the frontend had no
 * way to show which path produced a result — the whole point of the hybrid
 * rule-based/LLM-fallback pitch. handledBy/confidence/targetDb close that
 * gap; QueryGenerationServiceImpl already computed "source" internally but
 * was discarding it before returning.
 *
 * confidence is a fixed heuristic per source tier (RULE_ENGINE/CACHE are
 * deterministic matches, OLLAMA is a local LLM with no calibrated confidence
 * score of its own), not a computed statistical measure — see
 * QueryGenerationServiceImpl for the actual constants and rationale. This
 * keeps with the project's rule-based/interpretable design goal: no value
 * here is dressed up as more precise than it actually is.
 */
@Getter
@Setter
@AllArgsConstructor
public class QueryResponse {

    private String generatedQuery;
    private String targetDb;
    private String handledBy;   // "RULE_ENGINE" | "CACHE" | "OLLAMA"
    private Double confidence;

    /**
     * How the rule engine built this query — or why it declined and let
     * another layer answer.
     *
     * WHY IT IS PRESENT EVEN WHEN handledBy IS NOT RULE_ENGINE:
     * handledBy has always said WHICH layer answered and never why the
     * previous one passed. The rule engine runs first on every request, so
     * it has a reason either way, and discarding it left the project's
     * "routing is auditable" claim demonstrable only for the half of the
     * decision that succeeded.
     *
     * Null only for a response that never went through the rule engine at
     * all. Not persisted — QueryHistory keeps the shape it had.
     */
    private RuleTrace ruleTrace;
}
