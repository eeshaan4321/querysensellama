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
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.service.LlmFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cached answer belongs to the schema it was written against: asking the same question
 * after an upload must not serve SQL built for the previous schema.
 */
class SchemaScopedCacheTest {

    private static final KnownSchema.Table SALES = new KnownSchema.Table(
            "sales", List.of("id", "region", "total"));

    private static final SchemaSnapshot DEMO = KnownSchema.builtIn();
    private static final SchemaSnapshot UPLOADED = new SchemaSnapshot(
            "upload-1", "sample1.sql", "querysense_uploads",
            List.of(SALES), List.of(), Map.of(), Instant.EPOCH);

    private static final String DEMO_SQL = "SELECT id, name, city FROM customers;";
    private static final String UPLOADED_SQL = "SELECT id, region FROM sales;";

    private List<QueryHistory> stored;
    private LlmFallbackService llm;
    private SchemaSnapshot active;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        stored = new ArrayList<>();
        active = DEMO;

        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.active(Dialect.MYSQL)).thenAnswer(call -> active);
        when(activeSchema.tables(Dialect.MYSQL)).thenAnswer(call -> active.tables());
        when(activeSchema.findTable(eq(Dialect.MYSQL), anyString())).thenAnswer(call ->
                active.tables().stream()
                        .filter(t -> t.name().equalsIgnoreCase(call.getArgument(1)))
                        .findFirst());
        when(activeSchema.detectTablesMentioned(eq(Dialect.MYSQL), anyString())).thenReturn(List.of());

        QueryHistoryRepository history = mock(QueryHistoryRepository.class);
        when(history.save(any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return call.getArgument(0);
        });
        // The schema-blind lookup still answers, exactly as the database does, so a service
        // that keeps calling it fails these tests rather than passing them on an empty stub.
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(anyString(), anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(h -> h.getUserInput().equalsIgnoreCase(call.getArgument(0)))
                        .filter(h -> h.getDatabaseType().equalsIgnoreCase(call.getArgument(1)))
                        .findFirst());
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabel(
                anyString(), anyString(), anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(h -> h.getUserInput().equalsIgnoreCase(call.getArgument(0)))
                        .filter(h -> h.getDatabaseType().equalsIgnoreCase(call.getArgument(1)))
                        .filter(h -> call.getArgument(2).equals(h.getSchemaLabel()))
                        .findFirst());

        RuleBasedQueryBuilder rules = mock(RuleBasedQueryBuilder.class);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("too complex for the rule engine", null));

        llm = mock(LlmFallbackService.class);

        service = new QueryGenerationServiceImpl(
                rules, history, llm, new GeneratedQueryValidator(activeSchema), activeSchema);
    }

    @Test
    @DisplayName("an answer cached under the demo schema is not served after an upload")
    void cacheDoesNotCrossASchemaBoundary() {
        String question = "show me everything we have";
        when(llm.generateQuery(anyString(), anyString())).thenReturn(DEMO_SQL);

        QueryResponse onDemo = service.generateQuery(question, "MySQL");
        assertThat(onDemo.getHandledBy()).isEqualTo("OLLAMA");

        active = UPLOADED;
        when(llm.generateQuery(anyString(), anyString())).thenReturn(UPLOADED_SQL);

        QueryResponse afterUpload = service.generateQuery(question, "MySQL");

        assertThat(afterUpload.getHandledBy())
                .as("SQL written for the demo schema must not answer a question about the upload")
                .isEqualTo("OLLAMA");
        assertThat(afterUpload.getGeneratedQuery()).isEqualTo(UPLOADED_SQL);
    }

    @Test
    @DisplayName("the same question under the same schema still comes back from cache at 1.0")
    void cacheStillServesWithinOneSchema() {
        String question = "show me everything we have";
        when(llm.generateQuery(anyString(), anyString())).thenReturn(DEMO_SQL);

        service.generateQuery(question, "MySQL");
        QueryResponse second = service.generateQuery(question, "MySQL");

        assertThat(second.getHandledBy()).isEqualTo("CACHE");
        assertThat(second.getConfidence()).isEqualTo(1.0);
        assertThat(second.getGeneratedQuery()).isEqualTo(DEMO_SQL);
    }

    @Test
    @DisplayName("a row whose label still matches, but whose SQL no longer fits, is not served")
    void cachedSqlIsCheckedAgainstTheSchemaBeforeItIsServed() {
        String question = "show me everything we have";

        QueryHistory stale = new QueryHistory();
        stale.setUserInput(question);
        stale.setGeneratedQuery("SELECT buyer_id FROM transactions;");
        stale.setDatabaseType("MySQL");
        stale.setSource("OLLAMA");
        stale.setSchemaLabel(DEMO.label());
        stored.add(stale);

        when(llm.generateQuery(anyString(), anyString())).thenReturn(DEMO_SQL);

        QueryResponse response = service.generateQuery(question, "MySQL");

        assertThat(response.getHandledBy())
                .as("a cache hit naming tables the active schema does not have is a miss")
                .isEqualTo("OLLAMA");
        assertThat(response.getGeneratedQuery()).isEqualTo(DEMO_SQL);
    }
}
