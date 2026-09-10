package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;

import java.util.List;
import java.util.Optional;

/**
 * RuleBuildResult
 *
 * What the rule engine returns: either SQL with the trace that produced it,
 * or a decline with the reason it declined.
 *
 * WHY THIS EXISTS RATHER THAN A CHANGED tryBuild SIGNATURE:
 * RuleBasedQueryBuilderSchemaTest asserts on tryBuild's Optional<String> and
 * is the regression guard for schema adaptivity — the one file a refactor of
 * this area must not touch, on the same reasoning that kept KnownSchemaTest
 * unedited through the schema-upload work. So `build` is added returning
 * this type and `tryBuild` stays as a delegate over toOptional(). A test
 * that has to be rewritten alongside the code it guards is not a guard.
 */
public record RuleBuildResult(String sql, RuleTrace trace) {

    public static RuleBuildResult built(String sql, RuleTrace trace) {
        return new RuleBuildResult(sql, trace);
    }

    /**
     * @param reason           plain language, shown to the user — see RuleTrace's wording rule.
     * @param partialBindings  whatever did bind before the engine gave up, so the
     *                         trace can show how far it got rather than only that it stopped.
     */
    public static RuleBuildResult declined(String reason, List<RuleTrace.Binding> partialBindings) {
        return new RuleBuildResult(null,
                new RuleTrace(RuleTrace.DECLINED, null, partialBindings, null, List.of(), reason));
    }

    public boolean built() {
        return sql != null;
    }

    /** Bridge for the legacy tryBuild signature. See the class doc. */
    public Optional<String> toOptional() {
        return Optional.ofNullable(sql);
    }
}
