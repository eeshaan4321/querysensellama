package com.querysense.backend.repository;

import com.querysense.backend.model.QueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Data access for query history. */
@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, Long> {

    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(
            String userInput, String databaseType);
}