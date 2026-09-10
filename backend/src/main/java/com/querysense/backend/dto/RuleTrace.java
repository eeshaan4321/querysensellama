package com.querysense.backend.dto;

import java.util.List;

/**
 * RuleTrace
 *
 * How the rule engine built a query — or why it declined and handed the
 * question to the next layer.
 *
 * WHY DECLINES ARE TRACED TOO:
 * `handledBy` records WHICH layer answered but never why the previous one
 * passed. PROJECT.md §2 claims routing is "auditable rather than asserted";
 * without a decline reason that claim holds only for the half of the
 * decision that succeeded — you can see that the LLM answered, never that
 * the rules had a defensible reason for letting it. The reasons already
 * exist as logic in QueryIntentParser; this stops discarding them.
 *
 * WHY `origin` ON JoinEdge:
 * it is the field that makes the project's join claim checkable rather than
 * asserted. "declared foreign key" means a reader can open their own DDL and
 * confirm the ON clause was read from it, not guessed by a model — which is
 * the one thing about a generated join the LLM path cannot offer.
 *
 * WORDING RULE: every string here is user-facing and must stay free of
 * database vocabulary — the same discipline
 * SchemaAdvisorServiceImplTest.jargonFreeSummaries and VerdictServiceTest
 * enforce, and for the reason recorded there: copy drifts back toward the
 * vocabulary of the code around it. RuleTraceWordingTest is the guard.
 */
public record RuleTrace(
        String outcome,            // "BUILT" | "DECLINED"
        String shape,              // e.g. "SELECT + JOIN + GROUP BY + HAVING"; null when declined
        List<Binding> bindings,
        JoinEdge joinEdge,         // nullable — no join, or declined
        List<String> checksPassed,
        String declineReason) {    // null when built

    public static final String BUILT = "BUILT";
    public static final String DECLINED = "DECLINED";

    /**
     * One phrase from the question, and the schema element it resolved to.
     *
     * @param kind TABLE / COLUMN / FILTER / GROUPING / AGGREGATE / COMPARISON
     */
    public record Binding(String phrase, String kind, String resolvedTo) {}

    /** @param origin always "declared foreign key" — see the class doc. */
    public record JoinEdge(String fromTable, String fromColumn,
                           String toTable, String toColumn, String origin) {}

    public boolean built() {
        return BUILT.equals(outcome);
    }
}
