package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlEmitterTest
 *
 * Every case here asserts the emitted text exactly. There is not a sentence
 * of English anywhere in this file — that is the point of splitting the
 * emitter out of the parser: what comes out can be pinned character for
 * character, without a phrasing in the way.
 */
class SqlEmitterTest {

    private final SqlEmitter emitter = new SqlEmitter();

    @Test
    @DisplayName("a single-table select stays unqualified and byte-identical to today's output")
    void singleTableSelectIsUnqualified() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new QueryIntent.SelectItem("*", null)),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("a join qualifies every column and reads its ON clause from the intent")
    void joinQualifiesColumns() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers",
                new QueryIntent.JoinSpec("orders", "customers.id", "orders.customer_id"),
                List.of(new QueryIntent.SelectItem("customers.name", null)),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT customers.name FROM customers "
                        + "JOIN orders ON customers.id = orders.customer_id;");
    }

    @Test
    @DisplayName("GROUP BY covers every non-aggregated projection, so ONLY_FULL_GROUP_BY holds")
    void groupByCoversNonAggregatedProjections() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers",
                new QueryIntent.JoinSpec("orders", "customers.id", "orders.customer_id"),
                List.of(new QueryIntent.SelectItem("customers.id", null),
                        new QueryIntent.SelectItem("customers.name", null),
                        new QueryIntent.SelectItem("orders.id", "COUNT")),
                List.of(),
                List.of(new QueryIntent.Predicate("COUNT(orders.id)", ">", "3", false)),
                List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT customers.id, customers.name, COUNT(orders.id) FROM customers "
                        + "JOIN orders ON customers.id = orders.customer_id "
                        + "GROUP BY customers.id, customers.name "
                        + "HAVING COUNT(orders.id) > 3;");
    }

    @Test
    @DisplayName("no aggregate means no GROUP BY clause at all")
    void noAggregateMeansNoGroupBy() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new QueryIntent.SelectItem("name", null)),
                List.of(new QueryIntent.Predicate("city", "=", "mumbai", true)),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT name FROM customers WHERE city = 'mumbai';")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("order by and limit are appended in SQL order")
    void orderByThenLimit() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "products", null,
                List.of(new QueryIntent.SelectItem("*", null)),
                List.of(), List.of(), List.of("price DESC"), 5);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 5;");
    }

    @Test
    @DisplayName("DELETE emits its WHERE and never a projection")
    void deleteEmitsWhere() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.DELETE, "customers", null,
                List.of(),
                List.of(new QueryIntent.Predicate("city", "=", "pune", true)),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("DELETE FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("several predicates are joined with AND")
    void multiplePredicatesAreAnded() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "orders", null,
                List.of(new QueryIntent.SelectItem("*", null)),
                List.of(new QueryIntent.Predicate("status", "=", "shipped", true),
                        new QueryIntent.Predicate("customer_id", ">", "10", false)),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT * FROM orders WHERE status = 'shipped' AND customer_id > 10;");
    }

    @Test
    @DisplayName("COUNT(*) is an aggregate but groups by nothing when it is the only projection")
    void bareCountHasNoGroupBy() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "orders", null,
                List.of(new QueryIntent.SelectItem("*", "COUNT")),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT COUNT(*) FROM orders;")
                .doesNotContain("GROUP BY");
    }
}
