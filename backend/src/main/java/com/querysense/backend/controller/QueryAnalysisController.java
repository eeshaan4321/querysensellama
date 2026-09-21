package com.querysense.backend.controller;

import com.querysense.backend.dto.AnalyzeRequest;
import com.querysense.backend.dto.AnalyzeResponse;
import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.optimization.DomainOptimizationService;
import com.querysense.backend.optimization.ResourceConsciousService;
import com.querysense.backend.service.QueryAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/** REST endpoint for query analysis. */
@RestController
@RequestMapping("/api/query")
public class QueryAnalysisController {

    private final QueryAnalysisService queryAnalysisService;
    private final DomainOptimizationService domainOptimizationService;
    private final ResourceConsciousService resourceConsciousService;

    public QueryAnalysisController(QueryAnalysisService queryAnalysisService,
                                    DomainOptimizationService domainOptimizationService,
                                    ResourceConsciousService resourceConsciousService) {
        this.queryAnalysisService = queryAnalysisService;
        this.domainOptimizationService = domainOptimizationService;
        this.resourceConsciousService = resourceConsciousService;
    }

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@Valid @RequestBody AnalyzeRequest request) {
        QueryAnalysisResult result =
                queryAnalysisService.analyze(request.getQuery(), request.getDatabaseType());

        List<OptimizationSuggestion> domainSuggestions =
                domainOptimizationService.evaluate(result);
        List<OptimizationSuggestion> rescaledDomainSuggestions =
                resourceConsciousService.adjustSeverity(request.getResourceLevel(), domainSuggestions);
        List<OptimizationSuggestion> resourceSuggestions =
                resourceConsciousService.evaluate(request.getResourceLevel(), result);

        List<OptimizationSuggestion> allSuggestions = new ArrayList<>(rescaledDomainSuggestions);
        allSuggestions.addAll(resourceSuggestions);

        return new AnalyzeResponse(result, allSuggestions);
    }
}