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

/**
 * QueryAnalysisController
 *
 * WHY A SEPARATE CONTROLLER (not folded into QueryController):
 * QueryController owns generation + history, which are already a
 * cohesive pair. Analysis is a distinct concern with its own service
 * layer (QueryAnalysisService), so keeping it in its own controller
 * mirrors the existing separation and keeps each class focused —
 * same reasoning as why OllamaService and QueryGenerationServiceImpl
 * are separate rather than one giant service class.
 *
 * This endpoint replaces the throwaway ExplainTestController used for
 * manual verification — that class should be deleted once this is confirmed
 * working end-to-end.
 *
 * UPDATED: now also dispatches to DomainOptimizationService and
 * ResourceConsciousService and returns AnalyzeResponse instead of a bare
 * QueryAnalysisResult. Both services and the AnalyzeResponse/
 * OptimizationSuggestion DTOs already existed, but nothing was actually
 * calling them — AnalyzeRequest required domainProfile/resourceLevel on
 * every call without either ever being read. This is the fix: domain
 * suggestions get their severity re-scaled by resource level (per
 * ResourceConsciousService.adjustSeverity's own documented contract) before
 * being combined with resource's own independent suggestions.
 */
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
                domainOptimizationService.evaluate(request.getDomainProfile(), result);
        List<OptimizationSuggestion> rescaledDomainSuggestions =
                resourceConsciousService.adjustSeverity(request.getResourceLevel(), domainSuggestions);
        List<OptimizationSuggestion> resourceSuggestions =
                resourceConsciousService.evaluate(request.getResourceLevel(), result);

        List<OptimizationSuggestion> allSuggestions = new ArrayList<>(rescaledDomainSuggestions);
        allSuggestions.addAll(resourceSuggestions);

        return new AnalyzeResponse(result, allSuggestions);
    }
}