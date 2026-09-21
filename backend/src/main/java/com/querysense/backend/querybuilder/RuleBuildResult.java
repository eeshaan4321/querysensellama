package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;

import java.util.List;
import java.util.Optional;

/** SQL built by the rule engine, or the reason it declined, with its trace. */
public record RuleBuildResult(String sql, RuleTrace trace) {

    public static RuleBuildResult built(String sql, RuleTrace trace) {
        return new RuleBuildResult(sql, trace);
    }

    public static RuleBuildResult declined(String reason, List<RuleTrace.Binding> partialBindings) {
        return new RuleBuildResult(null,
                new RuleTrace(RuleTrace.DECLINED, null, partialBindings, null, List.of(), reason));
    }

    public boolean built() {
        return sql != null;
    }

    public Optional<String> toOptional() {
        return Optional.ofNullable(sql);
    }
}
