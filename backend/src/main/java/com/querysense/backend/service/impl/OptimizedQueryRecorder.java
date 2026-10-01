package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Writes an accepted rewrite into the history row of the query it improves. */
@Component
public class OptimizedQueryRecorder {

    private final QueryHistoryRepository repository;
    private final ActiveSchemaProvider activeSchema;

    public OptimizedQueryRecorder(QueryHistoryRepository repository, ActiveSchemaProvider activeSchema) {
        this.repository = repository;
        this.activeSchema = activeSchema;
    }

    public boolean record(Dialect dialect, String userInput, String originalQuery, String optimizedQuery) {
        String schemaLabel = activeSchema.active(dialect).label();
        Optional<QueryHistory> row;
        if (userInput != null && !userInput.isBlank()) {
            row = repository.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
                    userInput.trim(), dialect.label(), schemaLabel);
        } else {
            String original = originalQuery == null ? "" : originalQuery.trim();
            row = repository.findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
                            dialect.label(), schemaLabel).stream()
                    .filter(h -> h.getGeneratedQuery() != null && h.getGeneratedQuery().trim().equals(original))
                    .findFirst();
        }
        row.ifPresent(history -> {
            history.setOptimizedQuery(optimizedQuery);
            repository.save(history);
        });
        return row.isPresent();
    }
}
