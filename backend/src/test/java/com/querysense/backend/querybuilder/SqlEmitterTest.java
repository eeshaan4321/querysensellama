package com.querysense.backend.querybuilder;

import com.querysense.backend.querybuilder.QueryIntent.Aggregate;
import com.querysense.backend.querybuilder.QueryIntent.ColumnRef;
import com.querysense.backend.querybuilder.QueryIntent.Direction;
import com.querysense.backend.querybuilder.QueryIntent.Fn;
import com.querysense.backend.querybuilder.QueryIntent.JoinSpec;
import com.querysense.backend.querybuilder.QueryIntent.Literal;
import com.querysense.backend.querybuilder.QueryIntent.Op;
import com.querysense.backend.querybuilder.QueryIntent.OrderItem;
import com.querysense.backend.querybuilder.QueryIntent.Predicate;
import com.querysense.backend.querybuilder.QueryIntent.SelectItem;
import com.querysense.backend.querybuilder.QueryIntent.Star;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlEmitterTest {

    private final SqlEmitter emitter = new SqlEmitter();

    private static ColumnRef col(String column) {
        return new ColumnRef(null, column);
    }

    private static ColumnRef col(String table, String column) {
        return new ColumnRef(table, column);
    }

    @Test
    @DisplayName("a single-table select stays unqualified and byte-identical to today's output")
    void singleTableSelectIsUnqualified() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("a join qualifies every column and reads its ON clause from the intent")
    void joinQualifiesColumns() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("customers", "name"))),
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
                new JoinSpec("orders", col("customers", "id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("customers", "id")),
                        new SelectItem(col("customers", "name")),
                        new SelectItem(new Aggregate(Fn.COUNT, col("orders", "id")))),
                List.of(),
                List.of(new Predicate(new Aggregate(Fn.COUNT, col("orders", "id")), Op.GT,
                        List.of(Literal.number(3)))),
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
                List.of(new SelectItem(col("name"))),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("mumbai", "Mumbai")))),
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
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(), List.of(), List.of(new OrderItem(col("price"), Direction.DESC)), 5);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 5;");
    }

    @Test
    @DisplayName("DELETE emits its WHERE and never a projection")
    void deleteEmitsWhere() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.DELETE, "customers", null,
                List.of(),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("pune", "Pune")))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("DELETE FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("several predicates are joined with AND")
    void multiplePredicatesAreAnded() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "orders", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("status"), Op.EQ, List.of(Literal.text("shipped", "SHIPPED"))),
                        new Predicate(col("customer_id"), Op.GT, List.of(Literal.number(10)))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT * FROM orders WHERE status = 'shipped' AND customer_id > 10;");
    }

    @Test
    @DisplayName("COUNT(*) is an aggregate but groups by nothing when it is the only projection")
    void bareCountHasNoGroupBy() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "orders", null,
                List.of(new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT COUNT(*) FROM orders;")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("IN quotes every value, and prints the typed text rather than the stored value")
    void inQuotesTypedText() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("city"), Op.IN, List.of(
                        Literal.text("mumbai", "Mumbai"), Literal.text("pune", "Pune")))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT * FROM customers WHERE city IN ('mumbai', 'pune');");
    }

    @Test
    @DisplayName("a number written as a word prints as typed, exactly as before the refactor")
    void numberWordPrintsAsTyped() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("id"), Op.EQ, List.of(new Literal("three", true, 3)))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("SELECT * FROM customers WHERE id = three;");
    }
}
