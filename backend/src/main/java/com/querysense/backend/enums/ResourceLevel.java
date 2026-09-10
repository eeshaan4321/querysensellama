package com.querysense.backend.enums;

/**
 * ResourceLevel
 *
 * Coarse system-resource categories (e.g. available memory) that the
 * Resource-Conscious Decision System uses to scale how aggressively it
 * flags a given QueryAnalysisResult finding. Kept as three discrete,
 * rule-driven tiers rather than a computed or learned value, per the
 * capstone's design goal of staying interpretable rather than a black box
 * (see "How Good Are Learned Cost Models, Really?" in the literature
 * review).
 */
public enum ResourceLevel {
    LOW,
    MEDIUM,
    HIGH
}