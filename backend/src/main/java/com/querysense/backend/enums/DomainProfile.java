package com.querysense.backend.enums;

/**
 * DomainProfile
 *
 * The three workload types the Domain-Sensitive Optimization Module
 * supports, per the capstone synopsis. Each profile gets its own rule set
 * in optimization/ (e.g. EcommerceOptimizationRules) because the same
 * QueryAnalysisResult should produce different advice depending on which
 * of these a query belongs to — see Song et al. (2023), "Rethink Query
 * Optimization in HTAP Databases", cited in the literature review as the
 * justification for keeping these as separate, non-merged rule sets.
 */
public enum DomainProfile {
    ECOMMERCE,
    ANALYTICAL,
    TRANSACTIONAL
}