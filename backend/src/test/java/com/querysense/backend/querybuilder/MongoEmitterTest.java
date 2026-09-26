package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
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
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoEmitterTest {

    private final MongoEmitter emitter = new MongoEmitter(new ActiveSchemaProvider());
    private final MongoShellParser parser = new MongoShellParser();

    private static ColumnRef col(String column) {
        return new ColumnRef(null, column);
    }

    private static ColumnRef col(String table, String column) {
        return new ColumnRef(table, column);
    }

    private static QueryIntent select(String from, List<SelectItem> items, List<Predicate> where,
                                      List<OrderItem> orderBy, Integer limit) {
        return new QueryIntent(QueryIntent.Verb.SELECT, from, null, items, where, List.of(), orderBy, limit);
    }

    private String roundTrip(QueryIntent intent) {
        String emitted = emitter.emit(intent);
        MongoQuery parsed = parser.parse(emitted);
        assertThat(parsed.collection()).isEqualTo(intent.fromTable());
        return emitted;
    }

    @Test
    @DisplayName("serves MongoDB")
    void dialect() {
        assertThat(emitter.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("SELECT * is a find with no projection")
    void selectStar() {
        assertThat(roundTrip(select("customers", List.of(new SelectItem(Star.INSTANCE)), List.of(), List.of(), null)))
                .isEqualTo("db.customers.find({})");
    }

    @Test
    @DisplayName("a filter uses the stored value, not the typed one, because MongoDB matches case")
    void usesStoredValueNotTypedValue() {
        QueryIntent intent = select("customers", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("mumbai", "Mumbai")))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.find({\"city\": \"Mumbai\"})");
    }

    @Test
    @DisplayName("IN becomes $in, comparisons become $gt/$lt with numbers unquoted")
    void inAndComparisons() {
        QueryIntent intent = select("orders", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("status"), Op.IN, List.of(
                                Literal.text("pending", "PENDING"), Literal.text("shipped", "SHIPPED"))),
                        new Predicate(col("customer_id"), Op.GT, List.of(Literal.number(10)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo(
                "db.orders.find({\"status\": {\"$in\": [\"PENDING\", \"SHIPPED\"]}, \"customer_id\": {\"$gt\": 10}})");
    }

    @Test
    @DisplayName("two conditions on one field are kept, under $and")
    void sameFieldTwiceUsesAnd() {
        QueryIntent intent = select("products", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("price"), Op.GT, List.of(Literal.number(10))),
                        new Predicate(col("price"), Op.LT, List.of(Literal.number(50)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo(
                "db.products.find({\"$and\": [{\"price\": {\"$gt\": 10}}, {\"price\": {\"$lt\": 50}}]})");
    }

    @Test
    @DisplayName("named columns project those fields and hide _id, as SQL would not return it")
    void projectionHidesId() {
        QueryIntent intent = select("customers",
                List.of(new SelectItem(col("name")), new SelectItem(col("email"))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.find({}, {\"name\": 1, \"email\": 1, \"_id\": 0})");
    }

    @Test
    @DisplayName("sort and limit chain onto find")
    void sortAndLimit() {
        QueryIntent intent = select("products", List.of(new SelectItem(Star.INSTANCE)), List.of(),
                List.of(new OrderItem(col("price"), Direction.DESC)), 5);

        assertThat(roundTrip(intent)).isEqualTo("db.products.find({}).sort({\"price\": -1}).limit(5)");
    }

    @Test
    @DisplayName("a bare COUNT(*) is countDocuments")
    void bareCount() {
        QueryIntent intent = select("orders", List.of(new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.countDocuments({})");
    }

    @Test
    @DisplayName("DELETE is deleteMany with the filter")
    void delete() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.DELETE, "customers", null, List.of(),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("pune", "Pune")))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.deleteMany({\"city\": \"Pune\"})");
    }

    @Test
    @DisplayName("an aggregate with no grouping groups everything under a null _id")
    void ungroupedAggregate() {
        QueryIntent intent = select("products",
                List.of(new SelectItem(new Aggregate(Fn.AVG, col("price")))), List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo(
                "db.products.aggregate([{\"$group\": {\"_id\": null, \"avg_price\": {\"$avg\": \"$price\"}}}])");
    }

    @Test
    @DisplayName("a join through a reference is $lookup + $unwind, and HAVING is a $match after $group")
    void joinWithHaving() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "_id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("customers", "_id")),
                        new SelectItem(col("customers", "name")),
                        new SelectItem(new Aggregate(Fn.COUNT, col("orders", "_id")))),
                List.of(),
                List.of(new Predicate(new Aggregate(Fn.COUNT, col("orders", "_id")), Op.GT,
                        List.of(Literal.number(3)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.aggregate(["
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$group\": {\"_id\": {\"_id\": \"$_id\", \"name\": \"$name\"}, \"count_orders__id\": {\"$sum\": 1}}}, "
                + "{\"$match\": {\"count_orders__id\": {\"$gt\": 3}}}])");
    }

    @Test
    @DisplayName("aggregating a field inside an embedded array unwinds the array first")
    void embeddedArrayIsUnwoundBeforeGrouping() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(col("status")),
                        new SelectItem(new Aggregate(Fn.SUM, col("items.quantity")))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.aggregate(["
                + "{\"$unwind\": \"$items\"}, "
                + "{\"$group\": {\"_id\": \"$status\", \"sum_items_quantity\": {\"$sum\": \"$items.quantity\"}}}])");
    }

    @Test
    @DisplayName("sorting a grouped result by its group key sorts on _id")
    void sortAfterGroup() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(col("status")), new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(new OrderItem(col("status"), Direction.ASC)), 3);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.aggregate(["
                + "{\"$group\": {\"_id\": \"$status\", \"count\": {\"$sum\": 1}}}, "
                + "{\"$sort\": {\"_id\": 1}}, {\"$limit\": 3}])");
    }

    @Test
    @DisplayName("sorting a grouped result by a field it no longer has is refused, not approximated")
    void unexpressibleSortDeclines() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(col("status")), new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(new OrderItem(col("order_date"), Direction.ASC)), null);

        assertThatThrownBy(() -> emitter.emit(intent))
                .isInstanceOf(UnexpressibleIntentException.class)
                .hasMessageContaining("order_date");
    }

    @Test
    @DisplayName("a quote inside a value stays valid and reads back unchanged")
    void quoteInValueRoundTrips() {
        QueryIntent intent = select("customers", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("name"), Op.EQ, List.of(Literal.text("o'brien", "O'Brien \"Jr\"")))),
                List.of(), null);

        MongoQuery parsed = parser.parse(emitter.emit(intent));
        assertThat(parsed.filter().getString("name")).isEqualTo("O'Brien \"Jr\"");
    }

    // --- Fix round 1 ---

    @Test
    @DisplayName("a HAVING aggregate absent from the select list is grouped anyway, then hidden from the result")
    void havingOnlyAggregateIsGroupedThenProjectedAway() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "_id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("city")), new SelectItem(new Aggregate(Fn.AVG, col("customer_id")))),
                List.of(),
                List.of(new Predicate(new Aggregate(Fn.COUNT, col("orders", "_id")), Op.GT,
                        List.of(Literal.number(3)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.aggregate(["
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$group\": {\"_id\": \"$city\", \"avg_customer_id\": {\"$avg\": \"$customer_id\"}, \"count_orders__id\": {\"$sum\": 1}}}, "
                + "{\"$match\": {\"count_orders__id\": {\"$gt\": 3}}}, "
                + "{\"$project\": {\"count_orders__id\": 0}}])");
    }

    @Test
    @DisplayName("two HAVING conditions on the same aggregate are combined under $and")
    void repeatedHavingAliasUsesAnd() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "orders", null,
                List.of(new SelectItem(col("status")), new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(),
                List.of(new Predicate(new Aggregate(Fn.COUNT, null), Op.GT, List.of(Literal.number(3))),
                        new Predicate(new Aggregate(Fn.COUNT, null), Op.LT, List.of(Literal.number(10)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.aggregate(["
                + "{\"$group\": {\"_id\": \"$status\", \"count\": {\"$sum\": 1}}}, "
                + "{\"$match\": {\"$and\": [{\"count\": {\"$gt\": 3}}, {\"count\": {\"$lt\": 10}}]}}])");
    }

    @Test
    @DisplayName("a bare COUNT(*) with a join drops LIMIT rather than capping the count")
    void bareCountWithJoinIgnoresLimit() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "_id"), col("orders", "customer_id")),
                List.of(new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(), List.of(), 5);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.aggregate(["
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$count\": \"count\"}])");
    }

    @Test
    @DisplayName("a bare COUNT(*) with a join and ORDER BY builds without throwing, and drops the sort")
    void bareCountWithJoinIgnoresOrderBy() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "_id"), col("orders", "customer_id")),
                List.of(new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(), List.of(new OrderItem(col("order_date"), Direction.ASC)), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.aggregate(["
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$count\": \"count\"}])");
    }

    @Test
    @DisplayName("a WHERE predicate on a joined table's column is refused; $match cannot see it before $lookup")
    void whereOnJoinedTableColumnDeclines() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "_id"), col("orders", "customer_id")),
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("orders", "status"), Op.EQ, List.of(Literal.text("shipped", "SHIPPED")))),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> emitter.emit(intent))
                .isInstanceOf(UnexpressibleIntentException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("filtering on an embedded array's field while aggregating is refused, not silently mis-scoped")
    void whereOnEmbeddedArrayFieldDuringAggregateDeclines() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(new Aggregate(Fn.SUM, col("items.quantity")))),
                List.of(new Predicate(col("items.product_id"), Op.EQ, List.of(Literal.number(3)))),
                List.of(), null);

        assertThatThrownBy(() -> emitter.emit(intent))
                .isInstanceOf(UnexpressibleIntentException.class)
                .hasMessageContaining("items.product_id");
    }

    @Test
    @DisplayName("a non-aggregating find may still filter on an embedded array's field")
    void whereOnEmbeddedArrayFieldWithoutAggregateStillBuilds() {
        QueryIntent intent = select("orders", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("items.quantity"), Op.EQ, List.of(Literal.number(5)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.find({\"items.quantity\": 5})");
    }

    @Test
    @DisplayName("COUNT(column) excludes nulls via $cond instead of counting them with $sum: 1")
    void countColumnExcludesNullsViaCond() {
        QueryIntent intent = select("customers",
                List.of(new SelectItem(col("city")), new SelectItem(new Aggregate(Fn.COUNT, col("email")))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.aggregate(["
                + "{\"$group\": {\"_id\": \"$city\", \"count_email\": "
                + "{\"$sum\": {\"$cond\": [{\"$eq\": [{\"$ifNull\": [\"$email\", null]}, null]}, 0, 1]}}}}])");
    }
}
