package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizedQueryRecorderTest {

    private static final String MONGO_LABEL = KnownMongoSchema.builtIn().label();

    private QueryHistoryRepository repository;
    private OptimizedQueryRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(QueryHistoryRepository.class);
        recorder = new OptimizedQueryRecorder(repository, new ActiveSchemaProvider());
    }

    private static QueryHistory row(String generated) {
        QueryHistory history = new QueryHistory();
        history.setGeneratedQuery(generated);
        return history;
    }

    @Test
    @DisplayName("with the user's question, the newest matching row for this database and schema gets the rewrite")
    void byUserInput() {
        QueryHistory row = row("db.customers.find({})");
        when(repository.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
                "customers in pune", "MongoDB", MONGO_LABEL)).thenReturn(Optional.of(row));

        boolean recorded = recorder.record(Dialect.MONGODB, "  customers in pune ", "db.customers.find({})", "REWRITTEN");

        assertThat(recorded).isTrue();
        assertThat(row.getOptimizedQuery()).isEqualTo("REWRITTEN");
        verify(repository).save(row);
    }

    @Test
    @DisplayName("without the question, the newest row whose generated query matches gets the rewrite")
    void byGeneratedQuery() {
        QueryHistory older = row("db.customers.find({})");
        QueryHistory other = row("db.orders.find({})");
        when(repository.findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc("MongoDB", MONGO_LABEL))
                .thenReturn(List.of(other, older));

        assertThat(recorder.record(Dialect.MONGODB, " ", " db.customers.find({}) ", "REWRITTEN")).isTrue();

        assertThat(older.getOptimizedQuery()).isEqualTo("REWRITTEN");
        assertThat(other.getOptimizedQuery()).isNull();
        verify(repository).save(older);
    }

    @Test
    @DisplayName("no matching row means nothing is written")
    void noRow() {
        when(repository.findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(any(), any()))
                .thenReturn(List.of());

        assertThat(recorder.record(Dialect.MYSQL, null, "SELECT 1", "SELECT 2")).isFalse();
        verify(repository, never()).save(any());
    }
}
