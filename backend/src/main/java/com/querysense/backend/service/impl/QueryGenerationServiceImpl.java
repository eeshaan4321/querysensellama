package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.service.LlmFallbackService;
import com.querysense.backend.service.QueryGenerationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Generates queries through the rule engine, cache and LLM, and records history. */
@Service
public class QueryGenerationServiceImpl implements QueryGenerationService {

    private final RuleBasedQueryBuilder ruleBasedQueryBuilder;
    private final QueryHistoryRepository queryHistoryRepository;
    private final LlmFallbackService llmFallbackService;
    private final GeneratedQueryValidator generatedQueryValidator;
    private final ActiveSchemaProvider activeSchema;

    private static final String SOURCE_RULE_ENGINE = "RULE_ENGINE";
    private static final String SOURCE_CACHE = "CACHE";
    private static final String SOURCE_LLM = "OLLAMA";

    private static final double CONFIDENCE_RULE_ENGINE = 0.95;
    private static final double CONFIDENCE_RULE_ENGINE_INTERPRETED = 0.85;
    private static final double CONFIDENCE_LLM = 0.65;
    private static final double CONFIDENCE_LLM_INCOMPLETE = 0.4;
    private static final double CONFIDENCE_LLM_OFF_SCHEMA = 0.2;

    public QueryGenerationServiceImpl(
            RuleBasedQueryBuilder ruleBasedQueryBuilder,
            QueryHistoryRepository queryHistoryRepository,
            LlmFallbackService llmFallbackService,
            GeneratedQueryValidator generatedQueryValidator,
            ActiveSchemaProvider activeSchema) {
        this.ruleBasedQueryBuilder = ruleBasedQueryBuilder;
        this.queryHistoryRepository = queryHistoryRepository;
        this.llmFallbackService = llmFallbackService;
        this.generatedQueryValidator = generatedQueryValidator;
        this.activeSchema = activeSchema;
    }

    @Override
    public QueryResponse generateQuery(String input, String databaseType, ResourceLevel resourceLevel) {

        long startedAt = System.nanoTime();
        String generatedSql;
        String source;
        double confidence;
        String schemaWarning = null;

        RuleBuildResult ruleResult = ruleBasedQueryBuilder.build(input, databaseType);

        RuleTrace trace = ruleResult.trace();

        if (ruleResult.refused()) {
            return new QueryResponse(null, databaseType, SOURCE_RULE_ENGINE, 0.0, trace, null, List.of());
        }

        if (ruleResult.built()) {
            generatedSql = ruleResult.sql();
            source = SOURCE_RULE_ENGINE;
            confidence = ruleResult.interpreted() ? CONFIDENCE_RULE_ENGINE_INTERPRETED : CONFIDENCE_RULE_ENGINE;

        } else {
            Optional<QueryHistory> cached = servableCacheHit(input, databaseType);

            if (cached.isPresent()) {
                generatedSql = cached.get().getGeneratedQuery();
                source = SOURCE_CACHE;
                confidence = cached.get().getConfidence() == null ? CONFIDENCE_LLM : cached.get().getConfidence();

            } else {
                generatedSql = llmFallbackService.generateQuery(input, databaseType);
                source = SOURCE_LLM;
                schemaWarning = generatedQueryValidator.unsafeStatement(generatedSql, databaseType)
                        .or(() -> generatedQueryValidator.namesUnknownIdentifiers(generatedSql, databaseType))
                        .orElse(null);

                if (schemaWarning != null) {
                    confidence = CONFIDENCE_LLM_OFF_SCHEMA;
                } else {
                    confidence = generatedQueryValidator.looksIncomplete(input, generatedSql, databaseType)
                            ? CONFIDENCE_LLM_INCOMPLETE
                            : CONFIDENCE_LLM;
                }
            }
        }

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (schemaWarning == null) {
            saveToHistory(input, generatedSql, databaseType, source, confidence, durationMs, resourceLevel);
        }

        return new QueryResponse(generatedSql, databaseType, source, confidence, trace, schemaWarning,
                ruleResult.built() ? ruleResult.interpretations() : List.of());
    }

    /** A cached answer serves only if the LLM wrote it for the active schema and it still fits that schema. */
    private Optional<QueryHistory> servableCacheHit(String input, String databaseType) {
        return queryHistoryRepository
                .findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
                        input, databaseType, activeSchema.active(Dialect.from(databaseType)).label(), SOURCE_LLM)
                .filter(row -> generatedQueryValidator.unsafeStatement(row.getGeneratedQuery(), databaseType).isEmpty()
                        && generatedQueryValidator.namesUnknownIdentifiers(row.getGeneratedQuery(), databaseType).isEmpty());
    }

    private void saveToHistory(String input, String generatedSql, String databaseType,
                               String source, double confidence, long durationMs, ResourceLevel resourceLevel) {
        QueryHistory history = new QueryHistory();
        history.setUserInput(input);
        history.setGeneratedQuery(generatedSql);
        history.setDatabaseType(databaseType);
        history.setSource(source);
        history.setConfidence(confidence);
        history.setDurationMs(durationMs);
        history.setSchemaLabel(activeSchema.active(Dialect.from(databaseType)).label());
        history.setResourceLevel(resourceLevel);
        history.setCreatedAt(LocalDateTime.now());

        queryHistoryRepository.save(history);
    }
}
