package com.querysense.backend.repository;

import com.querysense.backend.model.QueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, Long> {

    // Scoped by databaseType too — the same natural-language text asked for
    // MySQL vs. MongoDB must not return the other dialect's cached query.
    // (findFirstByUserInputIgnoreCase alone was doing exactly that.)
    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(
            String userInput, String databaseType);
}