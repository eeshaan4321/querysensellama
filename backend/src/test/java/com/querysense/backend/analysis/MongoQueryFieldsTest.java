package com.querysense.backend.analysis;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MongoQueryFieldsTest {

    private final MongoShellParser parser = new MongoShellParser();

    private MongoQueryShape shape(String mongosh, String... leading) {
        return MongoQueryFields.shape(parser.parse(mongosh), Set.of(leading));
    }

    @Test
    @DisplayName("filter fields split into equality, range and unanchored regex, and the sort keeps direction")
    void classifiesFilterAndSort() {
        MongoQueryShape shape = shape("db.payments.find({\"method\": \"UPI\", \"amount\": {\"$gte\": 10}, "
                + "\"order_id\": {\"$in\": [1, 2]}, \"note\": {\"$regex\": \"pi\"}}).sort({\"payment_date\": -1})");

        assertThat(shape.collection()).isEqualTo("payments");
        assertThat(shape.equalityFields()).containsExactly("method", "order_id");
        assertThat(shape.rangeFields()).containsExactly("amount");
        assertThat(shape.unanchoredRegexFields()).containsExactly("note");
        assertThat(shape.sortKeys()).containsExactly(new IndexSpec.KeyPart("payment_date", -1));
    }

    @Test
    @DisplayName("ESR orders the index keys equality, sort, range, each field once")
    void esrOrder() {
        MongoQueryShape shape = shape("db.payments.find({\"amount\": {\"$gt\": 5}, \"method\": \"UPI\"})"
                + ".sort({\"payment_date\": -1, \"method\": 1})");

        assertThat(shape.esrKeys()).containsExactly(
                new IndexSpec.KeyPart("method", 1),
                new IndexSpec.KeyPart("payment_date", -1),
                new IndexSpec.KeyPart("amount", 1));
        assertThat(shape.sortIndexKeys()).containsExactly(
                new IndexSpec.KeyPart("method", 1),
                new IndexSpec.KeyPart("payment_date", -1));
    }

    @Test
    @DisplayName("an anchored regex is a range, and $and is flattened while $or is left out")
    void anchoredRegexAndLogic() {
        MongoQueryShape shape = shape("db.customers.find({\"name\": {\"$regex\": \"^Ra\"}, "
                + "\"$and\": [{\"city\": \"Pune\"}], \"$or\": [{\"phone\": \"1\"}]})");

        assertThat(shape.rangeFields()).containsExactly("name");
        assertThat(shape.equalityFields()).containsExactly("city");
        assertThat(shape.unanchoredRegexFields()).isEmpty();
    }

    @Test
    @DisplayName("an aggregate's leading $match and $sort are its filter and sort; later stages are not")
    void aggregateLeadingStages() {
        MongoQueryShape shape = shape("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$sort\": {\"order_date\": -1}}, {\"$group\": {\"_id\": \"$customer_id\"}}, "
                + "{\"$match\": {\"_id\": 1}}])");

        assertThat(shape.equalityFields()).containsExactly("status");
        assertThat(shape.sortKeys()).containsExactly(new IndexSpec.KeyPart("order_date", -1));
    }

    @Test
    @DisplayName("a $group with no $match on an indexed field before it is flagged; with one it is not")
    void groupWithoutIndexedMatch() {
        String unfiltered = "db.orders.aggregate([{\"$group\": {\"_id\": \"$status\"}}])";
        String unindexed = "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$group\": {\"_id\": \"$customer_id\"}}])";
        String indexed = "db.orders.aggregate([{\"$match\": {\"customer_id\": 1}}, "
                + "{\"$group\": {\"_id\": \"$status\"}}])";

        assertThat(shape(unfiltered, "customer_id").groupsWithoutIndexedMatch()).isTrue();
        assertThat(shape(unindexed, "customer_id").groupsWithoutIndexedMatch()).isTrue();
        assertThat(shape(indexed, "customer_id").groupsWithoutIndexedMatch()).isFalse();
        assertThat(shape("db.orders.find({})").groupsWithoutIndexedMatch()).isFalse();
    }

    @Test
    @DisplayName("referenced paths cover filter, projection inclusions, sort, $elemMatch and $or")
    void referencedPathsOfFind() {
        assertThat(MongoQueryFields.referencedPaths(parser.parse(
                "db.orders.find({\"$or\": [{\"status\": \"NEW\"}], \"items\": {\"$elemMatch\": {\"quantity\": 2}}}, "
                        + "{\"customer_id\": 1, \"employee_id\": 0}).sort({\"order_date\": 1})")))
                .containsExactly("status", "items", "items.quantity", "customer_id", "order_date");
    }

    @Test
    @DisplayName("pipeline paths stop after the first stage that reshapes documents")
    void referencedPathsOfPipeline() {
        assertThat(MongoQueryFields.referencedPaths(parser.parse(
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$sort\": {\"order_date\": 1}}, "
                        + "{\"$group\": {\"_id\": \"$customer_id\", \"total\": {\"$sum\": \"$items.quantity\"}}}, "
                        + "{\"$match\": {\"total\": {\"$gt\": 2}}}])")))
                .containsExactly("status", "order_date", "customer_id", "items.quantity");
    }

    @Test
    @DisplayName("lookups name their collections and fields, and whether they read the base documents")
    void lookups() {
        assertThat(MongoQueryFields.lookups(parser.parse(
                "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", \"localField\": \"customer_id\", "
                        + "\"foreignField\": \"_id\", \"as\": \"c\"}}, {\"$unwind\": \"$c\"}, "
                        + "{\"$lookup\": {\"from\": \"employees\", \"localField\": \"c.x\", "
                        + "\"foreignField\": \"_id\", \"as\": \"e\"}}])")))
                .containsExactly(
                        new MongoQueryFields.Lookup("customers", "customer_id", "_id", true),
                        new MongoQueryFields.Lookup("employees", "c.x", "_id", false));
    }

    @Test
    @DisplayName("$out and $merge are writes")
    void writes() {
        assertThat(MongoQueryFields.writesOutput(parser.parse("db.orders.aggregate([{\"$out\": \"copy\"}])"))).isTrue();
        assertThat(MongoQueryFields.writesOutput(parser.parse("db.orders.aggregate([{\"$merge\": \"copy\"}])"))).isTrue();
        assertThat(MongoQueryFields.writesOutput(parser.parse("db.orders.find({})"))).isFalse();
    }
}
