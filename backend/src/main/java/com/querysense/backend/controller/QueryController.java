package com.querysense.backend.controller;

import com.querysense.backend.dto.QueryRequest;
import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.service.QueryGenerationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST endpoints for query generation and history. */
@RestController
@RequestMapping("/api/query")
@CrossOrigin
public class QueryController {

    private final QueryGenerationService service;
    private final QueryHistoryRepository queryHistoryRepository;

    public QueryController(QueryGenerationService service,
                           QueryHistoryRepository queryHistoryRepository) {
        this.service = service;
        this.queryHistoryRepository = queryHistoryRepository;
    }

    @PostMapping("/generate")
    public QueryResponse generate(@Valid @RequestBody QueryRequest request) {
        return service.generateQuery(
                request.getQuery(),
                request.getDatabaseType()
        );
    }

    @GetMapping("/history")
    public List<QueryHistory> getHistory() {
        return queryHistoryRepository.findAll();
    }
}