package com.querysense.backend.model;

import com.querysense.backend.enums.ResourceLevel;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** A stored record of a generated query. */
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

    private String source;

    private Double confidence;

    private Long durationMs;

    private String schemaLabel;

    @Enumerated(EnumType.STRING)
    private ResourceLevel resourceLevel;

    private LocalDateTime createdAt;
}