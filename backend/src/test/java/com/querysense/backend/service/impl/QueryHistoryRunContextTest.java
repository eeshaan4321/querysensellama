package com.querysense.backend.service.impl;

import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.service.LlmFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Run context recorded alongside each generated query, so history can be analysed later. */
class QueryHistoryRunContextTest {

    private List<QueryHistory> stored;
    private RuleBasedQueryBuilder rules;
    private LlmFallbackService llm;
    private ActiveSchemaProvider activeSchema;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        stored = new ArrayList<>();

        QueryHistoryRepository history = mock(QueryHistoryRepository.class);
        when(history.save(any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(h -> h.getUserInput().equalsIgnoreCase(call.getArgument(0)))
                        .filter(h -> call.getArgument(2).equals(h.getSchemaLabel()))
                        .filter(h -> call.getArgument(3).equals(h.getSource()))
                        .reduce((older, newer) -> newer));

        rules = mock(RuleBasedQueryBuilder.class);
        llm = mock(LlmFallbackService.class);

        GeneratedQueryValidator validator = mock(GeneratedQueryValidator.class);
        when(validator.looksIncomplete(anyString(), anyString(), anyString())).thenReturn(false);
        when(validator.namesUnknownIdentifiers(anyString(), anyString())).thenReturn(Optional.empty());

        activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        service = new QueryGenerationServiceImpl(rules, history, llm, validator, activeSchema);
    }

    private void ruleEngineAnswers() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace));
    }

    @Test
    @DisplayName("the confidence returned is the confidence stored")
    void storesConfidence() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL");

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getConfidence)
                .isEqualTo(0.95);
    }

    @Test
    @DisplayName("a cache hit stores the cache tier's confidence, not the original run's")
    void cacheHitStoresItsOwnConfidence() {
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("too complex", null));
        when(llm.generateQuery(anyString(), anyString())).thenReturn("SELECT 1;");

        service.generateQuery("something complex", "MySQL");
        service.generateQuery("something complex", "MySQL");

        assertThat(stored).extracting(QueryHistory::getConfidence)
                .containsExactly(0.65, 0.65);
    }

    @Test
    @DisplayName("how long generation took is recorded, so a slow layer can be found later")
    void storesDuration() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL");

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getDurationMs)
                .isNotNull();
        assertThat(stored.get(0).getDurationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("the schema that was live is recorded, since uploads swap it between runs")
    void storesActiveSchemaLabel() {
        ruleEngineAnswers();
        when(activeSchema.active(Dialect.MYSQL)).thenReturn(new SchemaSnapshot(
                "upload-1", "shop.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers", List.of("id"))),
                List.of(), Map.of("customers", 500), Instant.now()));

        service.generateQuery("Show all customers", "MySQL");

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getSchemaLabel)
                .isEqualTo("shop.sql");
    }

    @Test
    @DisplayName("the built-in schema is recorded by name too, never left null")
    void storesBuiltInSchemaLabel() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL");

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getSchemaLabel)
                .isEqualTo(KnownSchema.builtIn().label());
    }

    @Test
    @DisplayName("an off-schema answer is still not stored, run context or not")
    void offSchemaIsStillNotStored() {
        GeneratedQueryValidator validator = mock(GeneratedQueryValidator.class);
        when(validator.namesUnknownIdentifiers(anyString(), anyString()))
                .thenReturn(Optional.of("names 'transactions', which is not in the active schema"));
        QueryHistoryRepository history = mock(QueryHistoryRepository.class);
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("too complex", null));
        when(llm.generateQuery(anyString(), anyString())).thenReturn("SELECT * FROM transactions;");

        new QueryGenerationServiceImpl(rules, history, llm, validator, activeSchema)
                .generateQuery("top buyers by spend", "MySQL");

        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("the resource level the run was made under is stored")
    void storesResourceLevel() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL", ResourceLevel.LOW);

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getResourceLevel)
                .isEqualTo(ResourceLevel.LOW);
    }

    @Test
    @DisplayName("a caller that sends no resource level stores none, rather than a guessed default")
    void noResourceLevelStoresNull() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL");

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getResourceLevel)
                .isNull();
    }
}
