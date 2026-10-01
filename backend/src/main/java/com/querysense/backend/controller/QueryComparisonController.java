package com.querysense.backend.controller;

import com.querysense.backend.dto.CompareRequest;
import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.service.QueryComparisonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for comparing a query against its rewrite. */
@RestController
@RequestMapping("/api/query")
public class QueryComparisonController {

    private final QueryComparisonService queryComparisonService;

    public QueryComparisonController(QueryComparisonService queryComparisonService) {
        this.queryComparisonService = queryComparisonService;
    }

    @PostMapping("/compare")
    public CompareResponse compare(@Valid @RequestBody CompareRequest request) {
        return queryComparisonService.compare(request.getQuery(), request.getDatabaseType(), request.getUserInput());
    }
}
