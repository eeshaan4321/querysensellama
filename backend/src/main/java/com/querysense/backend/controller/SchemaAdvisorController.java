package com.querysense.backend.controller;

import com.querysense.backend.dto.AnalyzeRequest;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.QueryAnalysisService;
import com.querysense.backend.service.SchemaAdvisorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST endpoint for schema recommendations. */
@RestController
@RequestMapping("/api/query")
public class SchemaAdvisorController {

    private final QueryAnalysisService queryAnalysisService;
    private final SchemaAdvisorService schemaAdvisorService;

    public SchemaAdvisorController(QueryAnalysisService queryAnalysisService,
                                    SchemaAdvisorService schemaAdvisorService) {
        this.queryAnalysisService = queryAnalysisService;
        this.schemaAdvisorService = schemaAdvisorService;
    }

    @PostMapping("/schema-advisor")
    public List<SchemaRecommendation> schemaAdvisor(@Valid @RequestBody AnalyzeRequest request) {
        QueryAnalysisResult analysisResult =
                queryAnalysisService.analyze(request.getQuery(), request.getDatabaseType());
        return schemaAdvisorService.recommend(analysisResult);
    }
}
