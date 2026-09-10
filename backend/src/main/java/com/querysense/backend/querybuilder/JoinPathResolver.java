package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JoinPathResolver
 *
 * Turns a pair of bound tables into the DECLARED foreign key that relates
 * them.
 *
 * WHY THIS IS THE WHOLE BASIS OF THE JOIN CLAIM:
 * SchemaSnapshot has carried foreignKeys() since the schema-upload work and
 * the generator has never read it. Reading it here means a rule-built join's
 * ON clause comes from the schema's own referential constraints — never
 * inferred from a naming convention like `client_id`, and never guessed. It
 * is the one thing about a generated join that the LLM path cannot claim,
 * and RuleTrace.JoinEdge.origin is what lets a reader check it against their
 * own DDL rather than take it on trust.
 *
 * WHY 2+ EDGES DECLINE RATHER THAN PICK THE FIRST:
 * `orders.billing_address_id` and `orders.shipping_address_id` both point at
 * `addresses.id` — an ordinary shape in a real schema. "orders with
 * addresses" genuinely has two right answers there, and picking one would be
 * exactly the confident-but-wrong output at RULE_ENGINE 0.95 that PROJECT.md
 * §5 names as the one failure the project's central claim cannot survive.
 * The built-in demo schema has no such pair, so this branch only ever fires
 * on an uploaded one — which is precisely why it has to exist.
 *
 * WHY SELF-REFERENCING EDGES ARE EXCLUDED:
 * `employees.manager_id -> employees.id` is a real FK, but a self-join needs
 * to know which side of the relationship the question means ("employees and
 * their managers" vs "managers and their reports"), and nothing in the
 * phrasing binds that. Out of scope; it declines.
 */
@Component
public class JoinPathResolver {

    public sealed interface JoinResolution permits Resolved, None, Ambiguous {}

    /** Exactly one declared foreign key relates the pair. */
    public record Resolved(KnownSchema.ForeignKey edge) implements JoinResolution {}

    /** No declared foreign key relates the pair. */
    public record None() implements JoinResolution {}

    /** Several declared foreign keys relate the pair; the question does not say which. */
    public record Ambiguous(List<KnownSchema.ForeignKey> edges) implements JoinResolution {}

    private final ActiveSchemaProvider activeSchema;

    public JoinPathResolver(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public JoinResolution resolve(String tableA, String tableB) {
        if (tableA == null || tableB == null) {
            return new None();
        }

        List<KnownSchema.ForeignKey> edges = activeSchema.foreignKeys().stream()
                .filter(fk -> !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .filter(fk -> relates(fk, tableA, tableB))
                .toList();

        if (edges.isEmpty()) {
            return new None();
        }
        if (edges.size() > 1) {
            return new Ambiguous(edges);
        }
        return new Resolved(edges.get(0));
    }

    private boolean relates(KnownSchema.ForeignKey fk, String a, String b) {
        return (fk.fromTable().equalsIgnoreCase(a) && fk.toTable().equalsIgnoreCase(b))
                || (fk.fromTable().equalsIgnoreCase(b) && fk.toTable().equalsIgnoreCase(a));
    }
}
