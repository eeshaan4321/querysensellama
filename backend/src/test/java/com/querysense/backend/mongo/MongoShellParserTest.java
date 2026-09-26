package com.querysense.backend.mongo;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoShellParserTest {

    private final MongoShellParser parser = new MongoShellParser();

    @Test
    @DisplayName("find with filter, projection, sort and limit")
    void findWithEverything() {
        MongoQuery q = parser.parse(
                "db.customers.find({\"city\": \"Mumbai\"}, {\"name\": 1, \"_id\": 0}).sort({\"name\": -1}).limit(10)");

        assertThat(q.collection()).isEqualTo("customers");
        assertThat(q.operation()).isEqualTo(MongoQuery.Operation.FIND);
        assertThat(q.filter()).isEqualTo(new Document("city", "Mumbai"));
        assertThat(q.projection()).isEqualTo(new Document("name", 1).append("_id", 0));
        assertThat(q.sort()).isEqualTo(new Document("name", -1));
        assertThat(q.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("shell syntax: unquoted keys, single quotes, a trailing semicolon")
    void shellSyntax() {
        MongoQuery q = parser.parse("db.orders.find({ status: 'SHIPPED', total: { $gt: 100 } });");

        assertThat(q.filter().getString("status")).isEqualTo("SHIPPED");
        assertThat(q.filter().get("total", Document.class)).isEqualTo(new Document("$gt", 100));
    }

    @Test
    @DisplayName("find() with no argument has an empty filter")
    void emptyFind() {
        MongoQuery q = parser.parse("db.products.find()");
        assertThat(q.filter()).isEmpty();
        assertThat(q.projection()).isNull();
    }

    @Test
    @DisplayName("aggregate reads its pipeline as a list of stages")
    void aggregatePipeline() {
        MongoQuery q = parser.parse(
                "db.orders.aggregate([{\"$match\": {\"status\": \"SHIPPED\"}}, {\"$group\": {\"_id\": \"$customer_id\", \"n\": {\"$sum\": 1}}}])");

        assertThat(q.operation()).isEqualTo(MongoQuery.Operation.AGGREGATE);
        assertThat(q.pipeline()).hasSize(2);
        assertThat(q.pipeline().get(0).containsKey("$match")).isTrue();
    }

    @Test
    @DisplayName("countDocuments and count are COUNT, deleteMany is DELETE")
    void countAndDelete() {
        assertThat(parser.parse("db.orders.countDocuments({})").operation()).isEqualTo(MongoQuery.Operation.COUNT);
        assertThat(parser.parse("db.orders.count()").operation()).isEqualTo(MongoQuery.Operation.COUNT);
        MongoQuery delete = parser.parse("db.customers.deleteMany({\"city\": \"Pune\"})");
        assertThat(delete.operation()).isEqualTo(MongoQuery.Operation.DELETE);
        assertThat(delete.filter()).isEqualTo(new Document("city", "Pune"));
    }

    @Test
    @DisplayName("a comma or bracket inside a string does not split the arguments")
    void delimitersInsideStrings() {
        MongoQuery q = parser.parse("db.customers.find({\"name\": \"o'brien, (jr) {x}\"})");
        assertThat(q.filter().getString("name")).isEqualTo("o'brien, (jr) {x}");
    }

    @Test
    @DisplayName("ObjectId and ISODate are understood")
    void shellTypes() {
        MongoQuery q = parser.parse(
                "db.orders.find({_id: ObjectId(\"64b7f0c2a1b2c3d4e5f60718\"), order_date: {$gte: ISODate(\"2026-01-01T00:00:00Z\")}})");
        assertThat(q.filter().get("_id")).isNotNull();
        assertThat(q.filter().get("order_date", Document.class).get("$gte")).isInstanceOf(java.util.Date.class);
    }

    @Test
    @DisplayName("anything that is not a supported query fails with a position")
    void failures() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM customers"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("db.");
        assertThatThrownBy(() -> parser.parse("db.customers.drop()"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("drop");
        assertThatThrownBy(() -> parser.parse("db.customers.find({city: 'x'}"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("closing");
        assertThatThrownBy(() -> parser.parse("db.customers.find({city: }"))
                .isInstanceOf(MongoShellParseException.class);
        assertThatThrownBy(() -> parser.parse("db.customers.find({}).explain()"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("explain");
        assertThatThrownBy(() -> parser.parse("db.orders.aggregate([]).limit(3)"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("find()");
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(MongoShellParseException.class);
    }

    @Test
    @DisplayName("text after the query is refused, not ignored")
    void trailingTextRefused() {
        assertThatThrownBy(() -> parser.parse("db.customers.find({}) db.orders.find({})"))
                .isInstanceOf(MongoShellParseException.class)
                .satisfies(e -> assertThat(((MongoShellParseException) e).position()).isGreaterThan(0));
    }
}
