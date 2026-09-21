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

/** Generates queries through the rule engine, cache and LLM, and records history. */
@Service
public class QueryGenerationServiceImpl implements QueryGenerationService {

    private final RuleBasedQueryBuilder ruleBasedQueryBuilder;
    private final QueryHistoryRepository queryHistoryRepository;
    private final LlmFallbackService llmFallbackService;
    private final GeneratedQueryValidator generatedQueryValidator;

    private static final String SOURCE_RULE_ENGINE = "RULE_ENGINE";
    private static final String SOURCE_CACHE = "CACHE";
    private static final String SOURCE_LLM = "OLLAMA";

    private static final double CONFIDENCE_RULE_ENGINE = 0.95;
    private static final double CONFIDENCE_CACHE = 1.0;
    private static final double CONFIDENCE_LLM = 0.65;
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

        RuleBuildResult ruleResult = ruleBasedQueryBuilder.build(input, databaseType);

        RuleTrace trace = ruleResult.trace();

        if (ruleResult.built()) {
            generatedSql = ruleResult.sql();
            source = SOURCE_RULE_ENGINE;
            confidence = CONFIDENCE_RULE_ENGINE;

        } else {
            Optional<QueryHistory> cached = queryHistoryRepository
                    .findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(input, databaseType);

            if (cached.isPresent()) {
                generatedSql = cached.get().getGeneratedQuery();
                source = SOURCE_CACHE;
                confidence = CONFIDENCE_CACHE;

            } else {
                generatedSql = llmFallbackService.generateQuery(input, databaseType);
                source = SOURCE_LLM;
                confidence = generatedQueryValidator.looksIncomplete(input, generatedSql, databaseType)
                        ? CONFIDENCE_LLM_INCOMPLETE
                        : CONFIDENCE_LLM;
            }
        }

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
