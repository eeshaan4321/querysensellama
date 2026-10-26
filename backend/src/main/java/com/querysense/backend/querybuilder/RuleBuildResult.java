package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;

import java.util.List;
import java.util.Optional;

/** SQL built by the rule engine, or the reason it declined or refused, with its trace and how it read the question. */
public record RuleBuildResult(String sql, RuleTrace trace, List<Interpretation> interpretations) {

    public RuleBuildResult(String sql, RuleTrace trace) {
        this(sql, trace, List.of());
    }

    public static RuleBuildResult built(String sql, RuleTrace trace) {
        return new RuleBuildResult(sql, trace);
    }

    public static RuleBuildResult built(String sql, RuleTrace trace, List<Interpretation> interpretations) {
        return new RuleBuildResult(sql, trace, List.copyOf(interpretations));
    }

    public static RuleBuildResult declined(String reason, List<RuleTrace.Binding> partialBindings) {
        return new RuleBuildResult(null,
                new RuleTrace(RuleTrace.DECLINED, null, partialBindings, null, List.of(), reason));
    }

    public static RuleBuildResult refused(String reason, List<RuleTrace.Binding> partialBindings) {
        return new RuleBuildResult(null,
                new RuleTrace(RuleTrace.REFUSED, null, partialBindings, null, List.of(), reason));
    }

    public boolean built() {
        return sql != null;
    }

    public boolean refused() {
        return trace.refused();
    }

    public boolean interpreted() {
        return interpretations.stream().anyMatch(i -> i.kind() != SchemaLexicon.MatchKind.SCHEMA_ALIAS);
    }

    public Optional<String> toOptional() {
        return Optional.ofNullable(sql);
    }
}
