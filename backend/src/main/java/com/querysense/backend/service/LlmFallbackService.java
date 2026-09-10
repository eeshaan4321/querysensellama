package com.querysense.backend.service;

/**
 * LlmFallbackService
 *
 * WHY THIS INTERFACE EXISTS:
 * QueryGenerationServiceImpl used to depend on the concrete GeminiService
 * class directly, which broke this project's own documented convention
 * (CLAUDE.md Section 11: "new modules depend on the interface, never the
 * concrete class"). GeminiService's Javadoc claimed "if we ever swap Gemini
 * for a different LLM, we only change this file" — that turned out to be
 * almost, but not quite, true: the orchestrator's constructor named the
 * concrete type, so the swap touched two files instead of one. This
 * interface makes the original claim actually hold.
 *
 * WHY IT MATTERS BEYOND TIDINESS:
 * Layer 3 of the generation pipeline is the ONLY place in QuerySense that
 * uses an LLM at all — analysis, domain optimization, resource decisions
 * and schema advice are entirely rule-based. Keeping that single seam
 * behind one small abstraction is what makes the "rule-based-first, LLM as
 * a replaceable fallback" architecture (CLAUDE.md Section 2) a structural
 * fact about the code rather than just a claim in the report.
 *
 * Current implementation: {@link com.querysense.backend.service.impl.OllamaService}
 * (local Ollama runtime). The previous Gemini 2.5 Flash implementation was
 * removed in the switch to local inference; it remains in git history if a
 * hosted provider is ever needed again.
 */
public interface LlmFallbackService {

    /**
     * Converts natural language into an executable query.
     *
     * Only called after both the rule engine and the history cache have
     * declined the input — see QueryGenerationServiceImpl's layer ordering.
     *
     * @param userInput    the raw natural language request
     * @param databaseType "MySQL" or "MongoDB" — selects the target dialect
     * @return             the generated query text, already stripped of any
     *                     markdown fences or conversational padding
     * @throws com.querysense.backend.exception.LlmUnavailableException
     *         if the model cannot be reached or is not installed. Callers
     *         must NOT catch this and substitute a placeholder string — see
     *         that exception's Javadoc for why that mattered.
     */
    String generateQuery(String userInput, String databaseType);

    /**
     * Optionally pre-loads the model so the first real request doesn't pay
     * the cold-start cost. A no-op by default — an implementation backed by
     * a hosted API has nothing to warm up.
     *
     * Never throws: a warm-up is an optimisation, and failing to perform one
     * must not prevent the application from starting.
     */
    default void warmUp() {
        // no-op
    }
}
