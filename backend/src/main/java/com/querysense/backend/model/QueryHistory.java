package com.querysense.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userInput;

    @Column(columnDefinition = "TEXT")
    private String generatedQuery;

    @Column(columnDefinition = "TEXT")
    private String optimizedQuery;

    private String databaseType;

    // RULE_ENGINE | CACHE | OLLAMA — which layer produced generatedQuery.
    // Was computed in QueryGenerationServiceImpl but previously discarded
    // instead of being persisted here; now saved so /api/query/history can
    // show it too, not just the immediate /api/query/generate response.
    private String source;

    private LocalDateTime createdAt;
}