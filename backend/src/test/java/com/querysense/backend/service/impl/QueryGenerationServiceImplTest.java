package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryGenerationServiceImplTest {

    private RuleBasedQueryBuilder rules;
    private QueryHistoryRepository history;
    private LlmFallbackService llm;
    private GeneratedQueryValidator validator;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        rules = mock(RuleBasedQueryBuilder.class);
        history = mock(QueryHistoryRepository.class);
        llm = mock(LlmFallbackService.class);
        validator = mock(GeneratedQueryValidator.class);

        when(validator.looksIncomplete(anyString(), anyString(), anyString())).thenReturn(false);
        when(validator.namesUnknownIdentifiers(anyString(), anyString())).thenReturn(Optional.empty());
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabel(
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(history.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.active()).thenReturn(KnownSchema.builtIn());

        service = new QueryGenerationServiceImpl(rules, history, llm, validator, activeSchema);
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
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabel(
                anyString(), anyString(), anyString()))
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

        assertThat(QueryHistory.class.getDeclaredFields())
                .noneSatisfy(f -> assertThat(f.getName()).containsIgnoringCase("trace"));
    }

    @Test
    @DisplayName("SQL naming a table outside the schema is returned flagged, and never written to history")
    void hallucinatedSqlIsFlaggedAndNotCached() {
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("The question names no known table.", List.of()));
        when(llm.generateQuery(anyString(), anyString()))
                .thenReturn("SELECT buyer_id FROM transactions;");
        when(validator.namesUnknownIdentifiers(anyString(), anyString()))
                .thenReturn(Optional.of("'transactions' is not a table in the active schema."));

        QueryResponse response = service.generateQuery("top 5 buyers by spend", "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(response.getConfidence()).isEqualTo(0.2);
        assertThat(response.getSchemaWarning()).contains("transactions");
        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("LLM output that matches the schema is still cached")
    void conformingLlmOutputIsCached() {
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("The question asks about a period of time.", List.of()));
        when(llm.generateQuery(anyString(), anyString())).thenReturn("SELECT * FROM orders;");

        QueryResponse response = service.generateQuery("orders from last month", "MySQL");

        assertThat(response.getSchemaWarning()).isNull();
        assertThat(response.getConfidence()).isEqualTo(0.65);
        verify(history, times(1)).save(any());
    }

    @Test
    @DisplayName("a rule-built query is recorded as before")
    void ruleBuiltIsRecorded() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace));

        service.generateQuery("Show all customers", "MySQL");

        verify(history, times(1)).save(any());
    }
}
