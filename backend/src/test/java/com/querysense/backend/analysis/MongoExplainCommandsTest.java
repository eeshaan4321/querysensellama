package com.querysense.backend.analysis;

import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoExplainCommandsTest {

    private final MongoShellParser parser = new MongoShellParser();

    private Document explain(String mongosh) {
        return MongoExplainCommands.explain(parser.parse(mongosh));
    }

    @Test
    @DisplayName("find carries its filter, projection, sort and limit, at executionStats")
    void find() {
        Document command = explain(
                "db.customers.find({\"city\": \"Mumbai\"}, {\"name\": 1}).sort({\"name\": -1}).limit(5)");

        assertThat(command.getString("verbosity")).isEqualTo("executionStats");
        Document find = command.get("explain", Document.class);
        assertThat(find.getString("find")).isEqualTo("customers");
        assertThat(find.get("filter")).isEqualTo(new Document("city", "Mumbai"));
        assertThat(find.get("projection")).isEqualTo(new Document("name", 1));
        assertThat(find.get("sort")).isEqualTo(new Document("name", -1));
        assertThat(find.get("limit")).isEqualTo(5);
    }

    @Test
    @DisplayName("a bare find sends an empty filter and nothing else")
    void bareFind() {
        Document find = explain("db.customers.find()").get("explain", Document.class);

        assertThat(find.keySet()).containsExactly("find", "filter");
        assertThat(find.get("filter")).isEqualTo(new Document());
    }

    @Test
    @DisplayName("aggregate sends its pipeline and an empty cursor")
    void aggregate() {
        Document aggregate = explain("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}])")
                .get("explain", Document.class);

        assertThat(aggregate.getString("aggregate")).isEqualTo("orders");
        assertThat(aggregate.getList("pipeline", Document.class))
                .containsExactly(new Document("$match", new Document("status", "PAID")));
        assertThat(aggregate.get("cursor")).isEqualTo(new Document());
    }

    @Test
    @DisplayName("countDocuments becomes the count command with its query")
    void count() {
        Document count = explain("db.orders.countDocuments({\"status\": \"PAID\"})").get("explain", Document.class);

        assertThat(count).isEqualTo(new Document("count", "orders").append("query", new Document("status", "PAID")));
    }

    @Test
    @DisplayName("deleteMany is wrapped in explain, so it is planned and never run")
    void deleteIsWrappedInExplain() {
        Document command = explain("db.orders.deleteMany({\"status\": \"CANCELLED\"})");

        assertThat(command.keySet()).containsExactly("explain", "verbosity");
        Document delete = command.get("explain", Document.class);
        assertThat(delete.getString("delete")).isEqualTo("orders");
        assertThat(delete.getList("deletes", Document.class)).containsExactly(
                new Document("q", new Document("status", "CANCELLED")).append("limit", 0));
    }

    @Test
    @DisplayName("every operation is covered")
    void everyOperation() {
        assertThat(MongoQuery.Operation.values()).hasSize(4);
    }
}
