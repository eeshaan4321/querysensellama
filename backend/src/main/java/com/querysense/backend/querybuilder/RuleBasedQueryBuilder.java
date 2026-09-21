package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Rule engine entry point: builds SQL from a question or declines. */
@Component
public class RuleBasedQueryBuilder {

    private final QueryIntentParser parser;

    public RuleBasedQueryBuilder(QueryIntentParser parser) {
        this.parser = parser;
    }

    public RuleBuildResult build(String input, String databaseType) {
        if (input == null || input.isBlank()) {
            return RuleBuildResult.declined("The question was empty.", List.of());
        }
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return RuleBuildResult.declined(
                    "This engine builds SQL only, so MongoDB questions go to the language model.",
                    List.of());
        }
        return parser.parse(input);
    }

    public Optional<String> tryBuild(String input, String databaseType) {
        return build(input, databaseType).toOptional();
    }
}
