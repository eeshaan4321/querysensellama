package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoGeneratedQueryValidatorTest {

    private final GeneratedQueryValidator validator = new GeneratedQueryValidator(new ActiveSchemaProvider());

    @Test
    @DisplayName("an on-schema MongoDB query raises nothing")
    void onSchemaPasses() {
        assertThat(validator.namesUnknownIdentifiers(
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$lookup\": {\"from\": \"customers\", "
                        + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])", "MongoDB"))
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown collection, field or $lookup field is named, in the preflight's words")
    void offSchemaIsNamed() {
        assertThat(validator.namesUnknownIdentifiers("db.transactions.find({\"buyer\": 1})", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'transactions'"));
        assertThat(validator.namesUnknownIdentifiers("db.customers.find({\"age\": 30})", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'age'"));
        assertThat(validator.namesUnknownIdentifiers("db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"buyer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'buyer_id'"));
        assertThat(validator.namesUnknownIdentifiers(
                "db.orders.aggregate([{\"$group\": {\"_id\": \"$region\", \"n\": {\"$sum\": 1}}}])", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'region'"));
    }

    @Test
    @DisplayName("output that is not a readable mongosh query is flagged with the parser's reason")
    void unreadableIsFlagged() {
        assertThat(validator.namesUnknownIdentifiers("SELECT * FROM orders;", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).startsWith("This is not a MongoDB query QuerySense can read: "));
        assertThat(validator.namesUnknownIdentifiers("db.orders.update({}, {\"$set\": {\"a\": 1}})", "MongoDB"))
                .isPresent();
    }

    @Test
    @DisplayName("an LLM pipeline that writes is flagged")
    void writeStageIsOffSchema() {
        assertThat(validator.namesUnknownIdentifiers(
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$out\": \"paid\"}])", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("never runs a write"));
    }

    @Test
    @DisplayName("two related collections answered from one is incomplete; a $lookup or an embedded path is not")
    void mongoIncompleteness() {
        String question = "orders with their customers";

        assertThat(validator.looksIncomplete(question, "db.orders.find({})", "MongoDB")).isTrue();
        assertThat(validator.looksIncomplete(question, "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])", "MongoDB")).isFalse();
        assertThat(validator.looksIncomplete("orders and their products",
                "db.orders.find({\"items.product_id\": {\"$exists\": true}})", "MongoDB")).isFalse();
        assertThat(validator.looksIncomplete("paid orders", "db.orders.find({})", "MongoDB")).isFalse();
        assertThat(validator.looksIncomplete("customers and suppliers", "db.customers.find({})", "MongoDB")).isFalse();
    }

    @Test
    @DisplayName("an unknown database type is still left alone")
    void otherTypesUnchecked() {
        assertThat(validator.namesUnknownIdentifiers("anything", "Oracle")).isEmpty();
        assertThat(validator.looksIncomplete("orders with customers", "anything", "Oracle")).isFalse();
    }
}
