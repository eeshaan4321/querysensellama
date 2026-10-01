package com.querysense.backend.mongo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoShellWriterTest {

    private final MongoShellParser parser = new MongoShellParser();

    @ParameterizedTest
    @ValueSource(strings = {
            "db.customers.find({\"city\": \"Mumbai\"})",
            "db.customers.find({}, {\"name\": 1, \"_id\": 0}).sort({\"name\": -1}).limit(5)",
            "db.customers.find()",
            "db.orders.find({\"customer_id\": ObjectId(\"5f1d7f1b2c3a4b5c6d7e8f90\"), \"order_date\": {\"$gte\": ISODate(\"2026-01-01T00:00:00Z\")}})",
            "db.orders.find({\"n\": NumberLong(\"5\"), \"p\": NumberDecimal(\"9.99\"), \"r\": /^mum/i})",
            "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$group\": {\"_id\": \"$customer_id\", \"n\": {\"$sum\": 1}}}])",
            "db.orders.aggregate([])",
            "db.orders.countDocuments({\"status\": \"PAID\"})",
            "db.orders.deleteMany({\"status\": \"CANCELLED\"})"
    })
    @DisplayName("what the parser reads, the writer writes back to the same query")
    void roundTrips(String mongosh) {
        MongoQuery parsed = parser.parse(mongosh);

        assertThat(parser.parse(MongoShellWriter.write(parsed))).isEqualTo(parsed);
    }

    @Test
    @DisplayName("plain queries read like the rule engine's own output")
    void plainShape() {
        assertThat(MongoShellWriter.write(parser.parse("db.customers.find({\"city\": \"Mumbai\"})")))
                .isEqualTo("db.customers.find({\"city\": \"Mumbai\"})");
        assertThat(MongoShellWriter.write(parser.parse(
                "db.products.find({}).sort({\"price\": -1}).limit(5)")))
                .isEqualTo("db.products.find({}).sort({\"price\": -1}).limit(5)");
        assertThat(MongoShellWriter.write(parser.parse("db.orders.countDocuments({})")))
                .isEqualTo("db.orders.countDocuments({})");
    }

    @Test
    @DisplayName("an ObjectId is written in mongosh syntax")
    void objectIdInShellSyntax() {
        assertThat(MongoShellWriter.write(parser.parse(
                "db.orders.find({\"_id\": ObjectId(\"5f1d7f1b2c3a4b5c6d7e8f90\")})")))
                .contains("ObjectId(\"5f1d7f1b2c3a4b5c6d7e8f90\")");
    }
}
