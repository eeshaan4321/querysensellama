package com.querysense.backend.dto;

import java.util.List;

/** How the rule engine built a query, or why it declined. */
public record RuleTrace(
        String outcome,
        String shape,
        List<Binding> bindings,
        JoinEdge joinEdge,
        List<String> checksPassed,
        String declineReason,
        List<JoinEdge> joinEdges) {

    public static final String BUILT = "BUILT";
    public static final String DECLINED = "DECLINED";
    public static final String REFUSED = "REFUSED";

    public RuleTrace(String outcome, String shape, List<Binding> bindings, JoinEdge joinEdge,
                     List<String> checksPassed, String declineReason) {
        this(outcome, shape, bindings, joinEdge, checksPassed, declineReason,
                joinEdge == null ? List.of() : List.of(joinEdge));
    }

    public record Binding(String phrase, String kind, String resolvedTo) {}

    public record JoinEdge(String fromTable, String fromColumn,
                           String toTable, String toColumn, String origin) {}

    public boolean built() {
        return BUILT.equals(outcome);
    }

    public boolean refused() {
        return REFUSED.equals(outcome);
    }
}
