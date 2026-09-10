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
}
