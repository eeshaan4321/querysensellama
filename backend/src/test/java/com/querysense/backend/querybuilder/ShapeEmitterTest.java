package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeEmitterTest {

    private final SqlEmitter sql = new SqlEmitter();
    private final MongoEmitter mongo = new MongoEmitter(new ActiveSchemaProvider());

    private static QueryIntent.ColumnRef col(String column) {
        return new QueryIntent.ColumnRef(null, column);
    }

    private static QueryIntent.SelectItem item(QueryIntent.Operand operand) {
        return new QueryIntent.SelectItem(operand);
    }

    private static QueryIntent intent(String table, QueryIntent.JoinSpec join, List<QueryIntent.SelectItem> select,
                                      List<QueryIntent.Predicate> where, boolean distinct, boolean anyOf) {
        return new QueryIntent(QueryIntent.Verb.SELECT, table, join, select, where, List.of(), List.of(), null,
                distinct, anyOf);
    }

    private static final List<QueryIntent.SelectItem> ALL = List.of(item(QueryIntent.Star.INSTANCE));

    @Test
    @DisplayName("not-equal and not-in")
    void notEqual() {
        QueryIntent ne = intent("orders", null, ALL, List.of(new QueryIntent.Predicate(col("status"),
                QueryIntent.Op.NE, List.of(QueryIntent.Literal.text("shipped", "SHIPPED")))), false, false);
        assertThat(sql.emit(ne)).isEqualTo("SELECT * FROM orders WHERE status != 'shipped';");
        assertThat(mongo.emit(ne)).isEqualTo("db.orders.find({\"status\": {\"$ne\": \"SHIPPED\"}})");

        QueryIntent notIn = intent("customers", null, ALL, List.of(new QueryIntent.Predicate(col("city"),
                QueryIntent.Op.NOT_IN, List.of(QueryIntent.Literal.text("pune", "Pune"),
                QueryIntent.Literal.text("delhi", "Delhi")))), false, false);
        assertThat(sql.emit(notIn)).isEqualTo("SELECT * FROM customers WHERE city NOT IN ('pune', 'delhi');");
        assertThat(mongo.emit(notIn)).isEqualTo("db.customers.find({\"city\": {\"$nin\": [\"Pune\", \"Delhi\"]}})");
    }

    @Test
    @DisplayName("between")
    void between() {
        QueryIntent between = intent("products", null, ALL, List.of(new QueryIntent.Predicate(col("price"),
                QueryIntent.Op.BETWEEN, List.of(QueryIntent.Literal.number(new BigDecimal("100")),
                QueryIntent.Literal.number(new BigDecimal("500"))))), false, false);
        assertThat(sql.emit(between)).isEqualTo("SELECT * FROM products WHERE price BETWEEN 100 AND 500;");
        assertThat(mongo.emit(between)).isEqualTo("db.products.find({\"price\": {\"$gte\": 100, \"$lte\": 500}})");
    }

    @Test
    @DisplayName("anyOf ORs the conditions")
    void anyOf() {
        QueryIntent or = intent("customers", null, ALL, List.of(
                new QueryIntent.Predicate(col("city"), QueryIntent.Op.EQ, List.of(QueryIntent.Literal.text("mumbai", "Mumbai"))),
                new QueryIntent.Predicate(col("name"), QueryIntent.Op.EQ, List.of(QueryIntent.Literal.text("asha", "Asha")))),
                false, true);
        assertThat(sql.emit(or)).isEqualTo("SELECT * FROM customers WHERE (city = 'mumbai' OR name = 'asha');");
        assertThat(mongo.emit(or)).isEqualTo("db.customers.find({\"$or\": [{\"city\": \"Mumbai\"}, {\"name\": \"Asha\"}]})");
    }

    @Test
    @DisplayName("distinct")
    void distinct() {
        QueryIntent distinct = intent("customers", null, List.of(item(col("city"))), List.of(), true, false);
        assertThat(sql.emit(distinct)).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(mongo.emit(distinct)).isEqualTo("db.customers.aggregate([{\"$group\": {\"_id\": \"$city\"}}])");
    }

    @Test
    @DisplayName("anti-join, as rows and as a count")
    void antiJoin() {
        QueryIntent.JoinSpec anti = new QueryIntent.JoinSpec("orders",
                new QueryIntent.ColumnRef("customers", "id"), new QueryIntent.ColumnRef("orders", "customer_id"),
                QueryIntent.JoinKind.ANTI);

        QueryIntent rows = intent("customers", anti, List.of(item(new QueryIntent.TableStar("customers"))), List.of(), false, false);
        assertThat(sql.emit(rows)).isEqualTo("SELECT customers.* FROM customers LEFT JOIN orders "
                + "ON customers.id = orders.customer_id WHERE orders.customer_id IS NULL;");
        assertThat(mongo.emit(rows)).isEqualTo("db.customers.aggregate([{\"$lookup\": {\"from\": \"orders\", "
                + "\"localField\": \"id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$match\": {\"orders\": {\"$size\": 0}}}, {\"$project\": {\"orders\": 0}}])");

        QueryIntent count = intent("customers", anti,
                List.of(item(new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, null))), List.of(), false, false);
        assertThat(sql.emit(count)).isEqualTo("SELECT COUNT(*) FROM customers LEFT JOIN orders "
                + "ON customers.id = orders.customer_id WHERE orders.customer_id IS NULL;");
        assertThat(mongo.emit(count)).endsWith("{\"$project\": {\"orders\": 0}}, {\"$count\": \"count\"}])");
    }
}
