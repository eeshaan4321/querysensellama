package com.querysense.backend.dto;

import java.util.List;

/** How the rule engine built a query, or why it declined. */
public record RuleTrace(
        String outcome,
        String shape,
        List<Binding> bindings,
        JoinEdge joinEdge,
        List<String> checksPassed,
        String declineReason) {

    public static final String BUILT = "BUILT";
    public static final String DECLINED = "DECLINED";

    public record Binding(String phrase, String kind, String resolvedTo) {}

    public record JoinEdge(String fromTable, String fromColumn,
                           String toTable, String toColumn, String origin) {}

    public boolean built() {
        return BUILT.equals(outcome);
    }
}
