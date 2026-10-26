package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.service.LlmFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The PROJECT.md section 8 reproduction, automated: the same off-schema question asked
 * twice must not come back from the cache at confidence 1.0 the second time.
 */
class OffSchemaCacheRoundTripTest {

    private static final String HALLUCINATED =
            "SELECT buyer_id, SUM(amount) FROM transactions GROUP BY buyer_id;";
    private static final String GROUNDED =
            "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id;";

    private List<QueryHistory> stored;
    private LlmFallbackService llm;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        stored = new ArrayList<>();

        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.tables(Dialect.MYSQL)).thenReturn(KnownSchema.TABLES);
        when(activeSchema.findTable(eq(Dialect.MYSQL), anyString())).thenAnswer(call ->
                KnownSchema.TABLES.stream()
                        .filter(t -> t.name().equalsIgnoreCase(call.getArgument(1)))
                        .findFirst());
        when(activeSchema.detectTablesMentioned(eq(Dialect.MYSQL), anyString())).thenReturn(List.of());

        QueryHistoryRepository history = mock(QueryHistoryRepository.class);
        when(history.save(any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(h -> h.getUserInput().equalsIgnoreCase(call.getArgument(0)))
                        .filter(h -> h.getDatabaseType().equalsIgnoreCase(call.getArgument(1)))
                        .filter(h -> call.getArgument(2).equals(h.getSchemaLabel()))
                        .filter(h -> call.getArgument(3).equals(h.getSource()))
                        .reduce((older, newer) -> newer));

        RuleBasedQueryBuilder rules = mock(RuleBasedQueryBuilder.class);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("too complex for the rule engine", null));

        llm = mock(LlmFallbackService.class);

        when(activeSchema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        service = new QueryGenerationServiceImpl(
                rules, history, llm, new GeneratedQueryValidator(activeSchema), activeSchema);
    }

    @Test
    @DisplayName("an off-schema answer is never promoted to CACHE at 1.0 on a second ask")
    void offSchemaAnswerIsNotPromotedByTheCache() {
        String question = "list the top 5 buyers by total spend last month";
        when(llm.generateQuery(anyString(), anyString())).thenReturn(HALLUCINATED);

        QueryResponse first = service.generateQuery(question, "MySQL");
        QueryResponse second = service.generateQuery(question, "MySQL");

        assertThat(first.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(first.getConfidence()).isEqualTo(0.2);
        assertThat(first.getSchemaWarning()).contains("transactions");

        assertThat(second.getHandledBy())
                .as("the second ask must not be served from cache")
                .isEqualTo("OLLAMA");
        assertThat(second.getConfidence())
                .as("confidence must not climb on a repeat of the same bad answer")
                .isEqualTo(0.2);
        assertThat(second.getSchemaWarning()).contains("transactions");
    }

    @Test
    @DisplayName("an LLM DELETE with no WHERE is flagged at 0.2, never cached, and not served from an older cached row")
    void unqualifiedDeleteIsFlaggedAndNeverCached() {
        String question = "delete orders";
        QueryHistory older = new QueryHistory();
        older.setUserInput(question);
        older.setDatabaseType("MySQL");
        older.setSchemaLabel(KnownSchema.builtIn().label());
        older.setSource("OLLAMA");
        older.setGeneratedQuery("DELETE FROM orders;");
        older.setConfidence(0.65);
        stored.add(older);
        when(llm.generateQuery(anyString(), anyString())).thenReturn("DELETE FROM orders;");

        QueryResponse response = service.generateQuery(question, "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(response.getConfidence()).isEqualTo(0.2);
        assertThat(response.getSchemaWarning()).contains("every row");
        assertThat(stored).containsExactly(older);
    }

    @Test
    @DisplayName("nothing off-schema reaches the history table at all")
    void offSchemaAnswerIsNeverStored() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn(HALLUCINATED);

        service.generateQuery("list the top 5 buyers by total spend last month", "MySQL");

        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("a grounded answer still caches, and keeps its LLM confidence on the second ask")
    void groundedAnswerStillCaches() {
        String question = "list customers with their orders";
        when(llm.generateQuery(anyString(), anyString())).thenReturn(GROUNDED);

        QueryResponse first = service.generateQuery(question, "MySQL");
        QueryResponse second = service.generateQuery(question, "MySQL");

        assertThat(first.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(first.getSchemaWarning()).isNull();

        assertThat(second.getHandledBy())
                .as("the guard must not break caching for good answers")
                .isEqualTo("CACHE");
        assertThat(second.getConfidence()).isEqualTo(0.65);

        assertThat(stored)
                .as("history is a per-run log, so both runs are recorded")
                .hasSize(2);
        assertThat(stored).extracting(QueryHistory::getSource)
                .containsExactly("OLLAMA", "CACHE");
    }
}
