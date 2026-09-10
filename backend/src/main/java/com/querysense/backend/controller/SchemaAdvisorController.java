package com.querysense.backend.controller;

import com.querysense.backend.dto.AnalyzeRequest;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.QueryAnalysisService;
import com.querysense.backend.service.SchemaAdvisorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SchemaAdvisorController
 *
 * WHY A SEPARATE CONTROLLER (not folded into QueryAnalysisController):
 * Same reasoning QueryAnalysisController already documents for staying
 * separate from QueryController — this is a distinct concern (schema
 * recommendations vs. raw analysis) with its own service
 * (SchemaAdvisorService), so it gets its own controller rather than
 * growing QueryAnalysisController into a class with two responsibilities.
 *
 * WHY IT RE-RUNS QueryAnalysisService INSTEAD OF TAKING A
 * QueryAnalysisResult DIRECTLY IN THE REQUEST BODY:
 * Matches AnalyzeRequest's existing shape (query + databaseType) so the
 * frontend can call this endpoint the same way it calls /analyze,
 * without needing to first call /analyze, copy the response, and paste
 * it into a second request. The tradeoff (EXPLAIN runs twice if a caller
 * wants both endpoints) is acceptable at this project's scale.
 */
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
