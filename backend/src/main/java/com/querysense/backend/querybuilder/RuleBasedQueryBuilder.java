package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * RuleBasedQueryBuilder
 *
 * The rule engine's entry point. It orchestrates now rather than parses:
 * {@link QueryIntentParser} binds the question to the ACTIVE schema and
 * {@link SqlEmitter} turns the bound intent into SQL.
 *
 * WHAT CHANGED, AND WHY
 * (docs/superpowers/specs/2026-09-10-schema-adaptive-rule-engine-design.md):
 *
 * The old flow detected a table, checked the input against a hardcoded
 * English blacklist, and emitted. Three consequences, all of which this
 * replaces:
 *
 *   1. The blacklist could not follow an uploaded schema. Uploading one
 *      changed the engine's vocabulary but never what it was willing to
 *      claim, which is why uploading appeared to change nothing.
 *   2. Two tables mentioned was an automatic decline, because there was no
 *      join builder for it to hand off to. There is one now, and its ON
 *      clause is read from a declared foreign key rather than inferred.
 *   3. The WHERE-clause path hardcoded `city` and checked it against
 *      `customers.city`, so on any schema without those it emitted a wrong
 *      column and its own safety net went silently inert.
 *
 * Binding failure is the decline signal now, so the decision depends on the
 * schema that is actually loaded.
 *
 * MongoDB still declines here, before any binding is attempted: every
 * emitter downstream produces SQL syntax, and claiming a Mongo request would
 * return syntactically valid but wrong-dialect output — silently wrong
 * rather than merely unhandled. Unchanged from the previous implementation,
 * and it stays until a real Mongo builder exists.
 */
@Component
public class RuleBasedQueryBuilder {

    private final QueryIntentParser parser;

    public RuleBasedQueryBuilder(QueryIntentParser parser) {
        this.parser = parser;
    }

    /**
     * Build a query, or explain why not. The decline reason travels with the
     * result — see RuleTrace on why a discarded reason made the project's
     * routing claim only half-demonstrable.
     */
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

    /**
     * Retained deliberately. RuleBasedQueryBuilderSchemaTest asserts on this
     * signature and is the regression guard for schema adaptivity — the one
     * file a refactor of this area must not touch, on the same reasoning
     * that kept KnownSchemaTest unedited through the schema-upload work. A
     * test rewritten alongside the code it guards is not a guard.
     */
    public Optional<String> tryBuild(String input, String databaseType) {
        return build(input, databaseType).toOptional();
    }
}
