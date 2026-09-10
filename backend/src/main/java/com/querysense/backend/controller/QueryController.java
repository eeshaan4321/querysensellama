package com.querysense.backend.controller;

import com.querysense.backend.dto.QueryRequest;
import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.service.QueryGenerationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * QueryController — UPDATED
 *
 * Two changes from the previous version:
 *
 * 1. @Valid on @RequestBody
 *    WHY: This activates the validation annotations on QueryRequest.
 *    Without @Valid, @NotBlank and @Size are completely ignored.
 *    With @Valid, Spring validates the request BEFORE calling the service.
 *    If validation fails, MethodArgumentNotValidException is thrown,
 *    which our GlobalExceptionHandler catches and returns as clean JSON.
 *
 * 2. New GET /api/query/history endpoint
 *    WHY: Exposes the query history stored in MySQL so users (and the
 *    frontend) can see past queries and generated SQL. This is also
 *    what makes the cache valuable — users can review what was cached.
 */
@RestController
@RequestMapping("/api/query")
@CrossOrigin
public class QueryController {

    private final QueryGenerationService service;
    private final QueryHistoryRepository queryHistoryRepository;

    // Constructor injection — both dependencies injected by Spring
    public QueryController(QueryGenerationService service,
                           QueryHistoryRepository queryHistoryRepository) {
        this.service = service;
        this.queryHistoryRepository = queryHistoryRepository;
    }

    /**
     * POST /api/query/generate
     * Generates a SQL or MongoDB query from natural language input.
     *
     * @Valid triggers validation on QueryRequest before this method runs.
     * If validation fails, execution never reaches service.generateQuery().
     */
    @PostMapping("/generate")
    public QueryResponse generate(@Valid @RequestBody QueryRequest request) {
        return service.generateQuery(
                request.getQuery(),
                request.getDatabaseType()
        );
    }

    /**
     * GET /api/query/history
     * Returns all past queries stored in MySQL.
     *
     * WHY return List<QueryHistory> directly?
     * For now this is fine — it returns all fields including id, timestamps etc.
     * In a future step we can create a QueryHistoryDTO to control exactly
     * which fields are exposed (e.g. hide internal IDs from the frontend).
     *
     * Spring automatically serializes List<QueryHistory> to a JSON array.
     */
    @GetMapping("/history")
    public List<QueryHistory> getHistory() {
        return queryHistoryRepository.findAll();
    }
}