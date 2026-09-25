package com.querysense.backend.dto;

import java.util.List;

/** One node of MySQL's execution plan, with its children in plan order. */
public record PlanNode(
        String kind,
        String label,
        String accessType,
        String keyUsed,
        Long estimatedRows,
        Double cost,
        boolean usesFilesort,
        boolean usesTemporaryTable,
        List<PlanNode> children) {
}
