package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Rule engine entry point: builds a query in the requested dialect from a question, or declines. */
@Component
public class RuleBasedQueryBuilder {

    private final QueryIntentParser parser;

    public RuleBasedQueryBuilder(QueryIntentParser parser) {
        this.parser = parser;
    }

    public RuleBuildResult build(String input, String databaseType) {
        Dialect dialect = Dialect.from(databaseType);
        if (input == null || input.isBlank()) {
            return RuleBuildResult.declined("The question was empty.", List.of());
        }
        return parser.parse(input, dialect);
    }

    public Optional<String> tryBuild(String input, String databaseType) {
        return build(input, databaseType).toOptional();
    }
}
