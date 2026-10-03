package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainEmitterTest {

    private final SqlEmitter sql = new SqlEmitter();
    private final MongoEmitter mongo = new MongoEmitter(new ActiveSchemaProvider());

    private static QueryIntent.ColumnRef ref(String table, String column) {
        return new QueryIntent.ColumnRef(table, column);
    }

    private static final QueryIntent.JoinSpec TO_ORDER_ITEMS = new QueryIntent.JoinSpec("order_items",
            ref("products", "id"), ref("order_items", "product_id"));
    private static final QueryIntent.JoinSpec TO_ORDERS = new QueryIntent.JoinSpec("orders",
            ref("order_items", "order_id"), ref("orders", "id"));
    private static final QueryIntent.JoinSpec TO_CUSTOMERS = new QueryIntent.JoinSpec("customers",
            ref("orders", "customer_id"), ref("customers", "id"));

    private static QueryIntent chain(List<QueryIntent.SelectItem> select, List<QueryIntent.Predicate> where,
                                     boolean distinct, boolean anyOf) {
        return new QueryIntent(QueryIntent.Verb.SELECT, "products", TO_ORDER_ITEMS, select, where, List.of(),
                List.of(), null, distinct, anyOf, List.of(TO_ORDERS, TO_CUSTOMERS));
    }

    private static final QueryIntent.Predicate IN_PUNE = new QueryIntent.Predicate(ref("customers", "city"),
            QueryIntent.Op.EQ, List.of(QueryIntent.Literal.text("pune", "Pune")));

    @Test
    @DisplayName("a three-hop chain with a filter on the last table, each product once")
    void chainWithJoinedFilter() {
        QueryIntent intent = chain(List.of(new QueryIntent.SelectItem(new QueryIntent.TableStar("products"))),
                List.of(IN_PUNE), true, false);

        assertThat(sql.emit(intent)).isEqualTo("SELECT DISTINCT products.* FROM products "
                + "JOIN order_items ON products.id = order_items.product_id "
                + "JOIN orders ON order_items.order_id = orders.id "
                + "JOIN customers ON orders.customer_id = customers.id WHERE customers.city = 'pune';");
        assertThat(mongo.emit(intent)).isEqualTo("db.products.aggregate(["
                + "{\"$lookup\": {\"from\": \"order_items\", \"localField\": \"id\", \"foreignField\": \"product_id\", \"as\": \"order_items\"}}, "
                + "{\"$unwind\": \"$order_items\"}, "
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"order_items.order_id\", \"foreignField\": \"id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$lookup\": {\"from\": \"customers\", \"localField\": \"orders.customer_id\", \"foreignField\": \"id\", \"as\": \"customers\"}}, "
                + "{\"$unwind\": \"$customers\"}, "
                + "{\"$match\": {\"customers.city\": \"Pune\"}}, "
                + "{\"$group\": {\"_id\": \"$_id\", \"doc\": {\"$first\": \"$$ROOT\"}}}, "
                + "{\"$replaceRoot\": {\"newRoot\": \"$doc\"}}, "
                + "{\"$project\": {\"order_items\": 0, \"orders\": 0, \"customers\": 0}}])");
    }

    @Test
    @DisplayName("distinctSubjectCount: a count with joined filters counts each subject once")
    void distinctSubjectCount() {
        QueryIntent intent = chain(List.of(new QueryIntent.SelectItem(
                new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, ref("products", "id")))), List.of(IN_PUNE), true, false);

        assertThat(sql.emit(intent)).startsWith("SELECT COUNT(DISTINCT products.id) FROM products JOIN order_items");
        assertThat(mongo.emit(intent)).endsWith("{\"$project\": {\"order_items\": 0, \"orders\": 0, \"customers\": 0}}, "
                + "{\"$count\": \"count\"}])");
    }

    @Test
    @DisplayName("orAfterLookups: an OR touching a joined collection is matched after the last lookup")
    void orAfterLookups() {
        QueryIntent.Predicate cheap = new QueryIntent.Predicate(ref("products", "price"), QueryIntent.Op.LT,
                List.of(QueryIntent.Literal.number(new java.math.BigDecimal("10"))));
        QueryIntent intent = chain(List.of(new QueryIntent.SelectItem(new QueryIntent.TableStar("products"))),
                List.of(cheap, IN_PUNE), true, true);

        assertThat(mongo.emit(intent)).contains("{\"$unwind\": \"$customers\"}, "
                + "{\"$match\": {\"$or\": [{\"price\": {\"$lt\": 10}}, {\"customers.city\": \"Pune\"}]}}");
    }

    @Test
    @DisplayName("twoTableUnchanged: one join renders exactly as before")
    void twoTableUnchanged() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new QueryIntent.JoinSpec("orders", ref("customers", "id"), ref("orders", "customer_id")),
                List.of(new QueryIntent.SelectItem(QueryIntent.Star.INSTANCE)), List.of(), List.of(), List.of(), null);

        assertThat(sql.emit(intent)).isEqualTo("SELECT * FROM customers JOIN orders ON customers.id = orders.customer_id;");
    }
}
