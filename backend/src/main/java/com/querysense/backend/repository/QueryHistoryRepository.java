package com.querysense.backend.repository;

import com.querysense.backend.model.QueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Data access for query history. */
@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, Long> {

    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(
            String userInput, String databaseType);

    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabel(
            String userInput, String databaseType, String schemaLabel);

    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
            String userInput, String databaseType, String schemaLabel);

    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
            String userInput, String databaseType, String schemaLabel, String source);

    List<QueryHistory> findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
            String databaseType, String schemaLabel);
}
