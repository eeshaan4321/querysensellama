package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoGenerationValidationTest {

    private List<QueryHistory> stored;
    private ActiveSchemaProvider activeSchema;
    private LlmFallbackService llm;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        stored = new ArrayList<>();
        activeSchema = new ActiveSchemaProvider();

        QueryHistoryRepository history = mock(QueryHistoryRepository.class);
        when(history.save(any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return call.getArgument(0);
        });
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

        service = new QueryGenerationServiceImpl(rules, history, llm, new GeneratedQueryValidator(activeSchema), activeSchema);
    }

    @Test
    @DisplayName("an off-schema MongoDB answer is 0.2, carries the warning, and is never saved")
    void offSchemaMongoAnswer() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn("db.transactions.find({\"buyer\": 1})");

        QueryResponse response = service.generateQuery("top buyers", "MongoDB");

        assertThat(response.getConfidence()).isEqualTo(0.2);
        assertThat(response.getSchemaWarning()).contains("'transactions'");
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("a grounded MongoDB answer is 0.65 and saved; one missing the join is 0.4")
    void groundedAndIncomplete() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn("db.orders.find({\"status\": \"PAID\"})");

        assertThat(service.generateQuery("paid orders", "MongoDB").getConfidence()).isEqualTo(0.65);
        assertThat(service.generateQuery("orders with their customers", "MongoDB").getConfidence()).isEqualTo(0.4);
        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("a cached MongoDB answer written for an earlier schema is not served after the schema changes")
    void staleMongoCacheIsNotServed() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn("db.orders.find({\"status\": \"PAID\"})");
        service.generateQuery("paid orders", "MongoDB");
        assertThat(service.generateQuery("paid orders", "MongoDB").getHandledBy()).isEqualTo("CACHE");

        activeSchema.publish(new SchemaSnapshot("u", activeSchema.active(Dialect.MONGODB).label(), "querysense_uploads",
                List.of(new KnownSchema.Table("invoices", List.of("_id", "total"))), List.of(), Map.of(),
                Instant.now(), Dialect.MONGODB, List.of()));

        QueryResponse after = service.generateQuery("paid orders", "MongoDB");

        assertThat(after.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(after.getSchemaWarning()).contains("'orders'");
    }
}
