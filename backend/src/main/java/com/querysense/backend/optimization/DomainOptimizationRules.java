package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;

import java.util.List;

/**
 * DomainOptimizationRules
 *
 * One implementation per DomainProfile (EcommerceOptimizationRules,
 * AnalyticalOptimizationRules, TransactionalOptimizationRules). Each
 * takes the same QueryAnalysisResult — Anant's contract — and returns
 * whatever suggestions are relevant to that specific workload type.
 *
 * IMPORTANT: implementations must branch on result.getDatabaseType()
 * before reading dialect-specific fields. For "MySQL" results, read
 * accessType / hasFullTableScan / usesFilesort / usesTemporaryTable /
 * keyUsed / estimatedRows. For "MongoDB" results, only
 * hasUnindexedFilter and hasUnanchoredRegex are meaningful — the
 * MySQL-only fields will just be sitting at their defaults (false/null)
 * and should not be read for a Mongo result.
 */
public interface DomainOptimizationRules {

    List<OptimizationSuggestion> evaluate(QueryAnalysisResult result);
}