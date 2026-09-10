package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.service.LlmFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QueryGenerationServiceImplTest
 *
 * The orchestrator had no test of its own. These cover the one thing that
 * changed about it: the rule engine's reasoning now travels onto the
 * response, including when the rule engine is not what answered.
 */
class QueryGenerationServiceImplTest {

    private RuleBasedQueryBuilder rules;
    private QueryHistoryRepository history;
    private LlmFallbackService llm;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        rules = mock(RuleBasedQueryBuilder.class);
        history = mock(QueryHistoryRepository.class);
        llm = mock(LlmFallbackService.class);
        GeneratedQueryValidator validator = mock(GeneratedQueryValidator.class);

        when(validator.looksIncomplete(anyString(), anyString(), anyString())).thenReturn(false);
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(history.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new QueryGenerationServiceImpl(rules, history, llm, validator);
    }

    @Test
    @DisplayName("a rule-built query carries its trace and stays at confidence 0.95")
    void ruleBuiltCarriesTrace() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace));

        QueryResponse response = service.generateQuery("Show all customers", "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("RULE_ENGINE");
        assertThat(response.getConfidence()).isEqualTo(0.95);
        assertThat(response.getRuleTrace().outcome()).isEqualTo("BUILT");
    }

    @Test
    @DisplayName("an LLM answer carries the DECLINED trace explaining why the rules passed")
    void llmAnswerCarriesDeclineReason() {
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("The question asks about a period of time.", List.of()));
        when(llm.generateQuery(anyString(), anyString())).thenReturn("SELECT 1;");

        QueryResponse response = service.generateQuery("orders from last month", "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(response.getRuleTrace().outcome()).isEqualTo("DECLINED");
        assertThat(response.getRuleTrace().declineReason()).contains("period of time");
    }

    @Test
    @DisplayName("a cache hit also carries the decline reason, since the rules still passed on it")
    void cacheHitCarriesDeclineReason() {
        QueryHistory cached = new QueryHistory();
        cached.setGeneratedQuery("SELECT * FROM orders;");

        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("The question names three tables.", List.of()));
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(cached));

        QueryResponse response = service.generateQuery("something complex", "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("CACHE");
        assertThat(response.getConfidence()).isEqualTo(1.0);
        assertThat(response.getRuleTrace().declineReason()).contains("three tables");
    }

    @Test
    @DisplayName("the trace is not persisted — history keeps the shape it had")
    void traceIsNotPersisted() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace));

        service.generateQuery("Show all customers", "MySQL");

        // QueryHistory has no column for it, deliberately: PROJECT.md §5
        // records "history stores no run context" and this work does not
        // change that. Asserted so a later change has to be deliberate.
        assertThat(QueryHistory.class.getDeclaredFields())
                .noneSatisfy(f -> assertThat(f.getName()).containsIgnoringCase("trace"));
    }
}
