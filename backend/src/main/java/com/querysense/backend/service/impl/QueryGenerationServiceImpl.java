package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.service.LlmFallbackService;
import com.querysense.backend.service.QueryGenerationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * QueryGenerationServiceImpl
 *
 * The orchestrator has all 3 layers fully wired:
 *   Layer 1: Rule-Based Engine   (free, instant)
 *   Layer 2: Cache Check         (free, instant)
 *   Layer 3: LLM Fallback        (local Ollama, only when layers 1+2 miss)
 *
 * UPDATED — the fallback is now local. Three things changed here, and none of
 * them are cosmetic:
 *
 * 1. This class depends on the LlmFallbackService INTERFACE, not a concrete
 *    provider class. It previously named GeminiService directly, which broke
 *    the project's own interface-and-impl convention (CLAUDE.md Section 11)
 *    and meant swapping providers touched the orchestrator as well as the
 *    provider. It no longer does — this file has no idea which model runs.
 *
 * 2. Incompleteness checking moved out to GeneratedQueryValidator. It was
 *    never provider-specific logic (it compares the user's input against the
 *    generated text using KnownSchema), so it had no business living inside
 *    the class that makes HTTP calls.
 *
 * 3. An unreachable model is now an exception rather than a placeholder
 *    string, so it can no longer be persisted or cached. See below.
 */
@Service
public class QueryGenerationServiceImpl implements QueryGenerationService {

    private final RuleBasedQueryBuilder ruleBasedQueryBuilder;
    private final QueryHistoryRepository queryHistoryRepository;
    private final LlmFallbackService llmFallbackService;
    private final GeneratedQueryValidator generatedQueryValidator;

    /**
     * The label recorded in QueryHistory.source and returned as
     * QueryResponse.handledBy.
     *
     * WHY "OLLAMA" AND NOT A GENERIC "LLM_FALLBACK": the previous value was
     * "GEMINI" — it named the provider, not the tier — and history rows are an
     * audit trail. A row that says which engine actually produced a query
     * stays meaningful; one that says "some LLM" does not, and the existing
     * "GEMINI" rows in the table would become indistinguishable from new ones.
     * Consumers switching on this string need updating either way, since the
     * old value can no longer occur.
     */
    private static final String SOURCE_RULE_ENGINE = "RULE_ENGINE";
    private static final String SOURCE_CACHE = "CACHE";
    private static final String SOURCE_LLM = "OLLAMA";

    // Fixed per-source heuristic, not a computed statistical confidence —
    // RULE_ENGINE/CACHE are deterministic matches (either the rule claimed
    // the query or it didn't; either the input was seen before or it
    // wasn't), and the local model has no calibrated confidence score of its
    // own to surface. See QueryResponse's Javadoc for why these are
    // intentionally simple constants rather than something dressed up as more
    // precise than it is.
    private static final double CONFIDENCE_RULE_ENGINE = 0.95;
    private static final double CONFIDENCE_CACHE = 1.0;
    private static final double CONFIDENCE_LLM = 0.65;
    // Used when GeneratedQueryValidator flags that the input implied more
    // conditions (a join/aggregate/comparison shape) than the generated SQL
    // actually shows. Still a fixed heuristic tier, not a computed score, just
    // a lower one — so an apparently-partial answer isn't labeled with the
    // same confidence as a clean generation.
    private static final double CONFIDENCE_LLM_INCOMPLETE = 0.4;

    public QueryGenerationServiceImpl(
            RuleBasedQueryBuilder ruleBasedQueryBuilder,
            QueryHistoryRepository queryHistoryRepository,
            LlmFallbackService llmFallbackService,
            GeneratedQueryValidator generatedQueryValidator) {
        this.ruleBasedQueryBuilder = ruleBasedQueryBuilder;
        this.queryHistoryRepository = queryHistoryRepository;
        this.llmFallbackService = llmFallbackService;
        this.generatedQueryValidator = generatedQueryValidator;
    }

    @Override
    public QueryResponse generateQuery(String input, String databaseType) {

        String generatedSql;
        String source;
        double confidence;

        // ---------------------------------------------------------------
        // LAYER 1: Rule-Based Engine
        // Fast, free, no I/O. Handles the majority of simple queries.
        // ---------------------------------------------------------------
        RuleBuildResult ruleResult = ruleBasedQueryBuilder.build(input, databaseType);

        // The rule engine has a reason either way, and it travels with the
        // response whichever layer ends up answering. See QueryResponse.
        RuleTrace trace = ruleResult.trace();

        if (ruleResult.built()) {
            generatedSql = ruleResult.sql();
            source = SOURCE_RULE_ENGINE;
            confidence = CONFIDENCE_RULE_ENGINE;

        } else {
            // -----------------------------------------------------------
            // LAYER 2: Cache Check
            // Before calling the LLM, check if we've answered this before.
            // WHY case-insensitive match?
            // "Show all customers" and "show all customers" are the same
            // question. Normalizing case prevents duplicate LLM calls.
            // -----------------------------------------------------------
            Optional<QueryHistory> cached = queryHistoryRepository
                    .findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(input, databaseType);

            if (cached.isPresent()) {
                generatedSql = cached.get().getGeneratedQuery();
                source = SOURCE_CACHE;
                confidence = CONFIDENCE_CACHE;

            } else {
                // -------------------------------------------------------
                // LAYER 3: LLM Fallback (local Ollama)
                // Only reaches here if:
                //   - Rule engine couldn't handle the query (complex/ambiguous
                //     /any MongoDB request)
                //   - Cache has no record of this input
                //
                // If the model is unreachable this THROWS rather than
                // returning a placeholder. That is deliberate: the previous
                // implementation returned the string "-- Gemini unavailable,
                // please try again shortly." which then flowed through this
                // method as if it were a real query — labeled with a normal
                // confidence, returned with HTTP 200, and written to
                // query_history, where the Layer 2 lookup above would happily
                // serve it back as a cache hit at confidence 1.0 forever
                // after. Rare against a hosted API; routine against a local
                // runtime that isn't always running. See
                // LlmUnavailableException.
                // -------------------------------------------------------
                generatedSql = llmFallbackService.generateQuery(input, databaseType);
                source = SOURCE_LLM;
                confidence = generatedQueryValidator.looksIncomplete(input, generatedSql, databaseType)
                        ? CONFIDENCE_LLM_INCOMPLETE
                        : CONFIDENCE_LLM;
            }
        }

        // ---------------------------------------------------------------
        // PERSIST TO HISTORY
        // Save every query — rule-based, cached, or LLM.
        // WHY save rule-based results too?
        // History isn't just for caching LLM responses — it's also an
        // audit trail. Developers can review what queries were generated,
        // catch bugs in the rule engine, and track usage patterns.
        // ---------------------------------------------------------------
        saveToHistory(input, generatedSql, databaseType, source);

        return new QueryResponse(generatedSql, databaseType, source, confidence, trace);
    }

    private void saveToHistory(String input, String generatedSql,
                               String databaseType, String source) {
        QueryHistory history = new QueryHistory();
        history.setUserInput(input);
        history.setGeneratedQuery(generatedSql);
        history.setDatabaseType(databaseType);
        history.setSource(source);
        history.setCreatedAt(LocalDateTime.now());

        queryHistoryRepository.save(history);
    }
}
