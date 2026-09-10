package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.DomainProfile;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * DomainOptimizationService
 *
 * Dispatches a QueryAnalysisResult to the correct DomainOptimizationRules
 * implementation based on the caller's DomainProfile. QueryGenerationServiceImpl
 * (and any controller) should depend on this class only — it should never
 * need to know that EcommerceOptimizationRules, AnalyticalOptimizationRules,
 * and TransactionalOptimizationRules exist as separate classes underneath.
 *
 * Adding a fourth domain profile later means: add the enum constant, write
 * a new DomainOptimizationRules implementation, and register it in the map
 * below — nothing else in the codebase changes.
 */
@Service
public class DomainOptimizationService {

    private final Map<DomainProfile, DomainOptimizationRules> rulesByProfile;

    public DomainOptimizationService() {
        this.rulesByProfile = new EnumMap<>(DomainProfile.class);
        rulesByProfile.put(DomainProfile.ECOMMERCE, new EcommerceOptimizationRules());
        rulesByProfile.put(DomainProfile.ANALYTICAL, new AnalyticalOptimizationRules());
        rulesByProfile.put(DomainProfile.TRANSACTIONAL, new TransactionalOptimizationRules());
    }

    /**
     * Returns domain-specific suggestions for the given analysis result.
     *
     * @param domainProfile which rule set to apply. If null, defaults to
     *                      TRANSACTIONAL as the strictest, safest default —
     *                      change this to throw an IllegalArgumentException
     *                      instead if you kept @NotNull on AnalyzeRequest.domainProfile
     *                      and would rather fail loudly than guess.
     * @param result        Anant's analysis output — must not be null.
     */
    public List<OptimizationSuggestion> evaluate(DomainProfile domainProfile, QueryAnalysisResult result) {
        if (result == null) {
            throw new IllegalArgumentException("QueryAnalysisResult must not be null");
        }

        DomainProfile effectiveProfile = domainProfile != null ? domainProfile : DomainProfile.TRANSACTIONAL;

        DomainOptimizationRules rules = rulesByProfile.get(effectiveProfile);
        if (rules == null) {
            // Should be unreachable if every enum constant is registered above —
            // this guards against someone adding a DomainProfile value without
            // registering its rule class.
            throw new IllegalStateException("No DomainOptimizationRules registered for profile: " + effectiveProfile);
        }

        return rules.evaluate(result);
    }
}