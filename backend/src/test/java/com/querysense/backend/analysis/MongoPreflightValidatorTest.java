package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MongoPreflightValidatorTest {

    private final ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
    private final MongoPreflightValidator validator = new MongoPreflightValidator(activeSchema);
    private final MongoShellParser parser = new MongoShellParser();

    private Optional<String> check(String mongosh) {
        return validator.validate(parser.parse(mongosh));
    }

    @Test
    @DisplayName("a query on declared fields passes")
    void declaredPasses() {
        assertThat(check("db.customers.find({\"city\": \"Mumbai\"}, {\"name\": 1}).sort({\"name\": 1})")).isEmpty();
        assertThat(check("db.orders.find({\"items.quantity\": {\"$gt\": 2}})")).isEmpty();
        assertThat(check("db.orders.find({\"items.0.quantity\": 2})")).isEmpty();
        assertThat(check("db.orders.find({\"items\": {\"$size\": 2}})")).isEmpty();
        assertThat(check("db.customers.find({\"_id\": 1})")).isEmpty();
    }

    @Test
    @DisplayName("an unknown collection is refused, in the wording of SQL's P2")
    void unknownCollection() {
        assertThat(check("db.users.find({})")).hasValueSatisfying(m -> assertThat(m)
                .isEqualTo("Collection 'users' is not in the active MongoDB schema — this query references "
                        + "a collection that isn't part of the real schema."));
    }

    @Test
    @DisplayName("collection names are matched exactly, never by singular form")
    void exactCollectionName() {
        assertThat(check("db.customer.find({})")).isPresent();
    }

    @Test
    @DisplayName("an unknown field in the filter, projection or sort is refused, naming it")
    void unknownFields() {
        assertThat(check("db.customers.find({\"age\": 30})")).hasValueSatisfying(m -> assertThat(m)
                .isEqualTo("Field 'age' does not exist on 'customers' — this query references a field that "
                        + "isn't part of the real schema."));
        assertThat(check("db.customers.find({}, {\"age\": 1})")).isPresent();
        assertThat(check("db.customers.find({}).sort({\"age\": 1})")).isPresent();
        assertThat(check("db.customers.find({\"$or\": [{\"age\": 1}]})")).isPresent();
        assertThat(check("db.orders.find({\"items\": {\"$elemMatch\": {\"bogus\": 1}}})"))
                .hasValueSatisfying(m -> assertThat(m).contains("'items.bogus'"));
    }

    @Test
    @DisplayName("pipeline fields are checked up to the first reshaping stage, including $group's references")
    void pipelineFields() {
        assertThat(check("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$group\": {\"_id\": \"$status\", \"n\": {\"$sum\": 1}}}, {\"$match\": {\"n\": {\"$gt\": 2}}}])"))
                .isEmpty();
        assertThat(check("db.orders.aggregate([{\"$group\": {\"_id\": \"$nope\"}}])"))
                .hasValueSatisfying(m -> assertThat(m).contains("'nope'"));
    }

    @Test
    @DisplayName("a $lookup's collection, local field and foreign field are all checked")
    void lookupChecked() {
        String valid = "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", \"localField\": "
                + "\"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])";
        assertThat(check(valid)).isEmpty();
        assertThat(check(valid.replace("\"customers\"", "\"clients\""))).hasValueSatisfying(m ->
                assertThat(m).contains("'clients'"));
        assertThat(check(valid.replace("\"customer_id\"", "\"client_id\""))).hasValueSatisfying(m ->
                assertThat(m).contains("'client_id'").contains("'orders'"));
        assertThat(check(valid.replace("\"foreignField\": \"_id\"", "\"foreignField\": \"code\""))).hasValueSatisfying(m ->
                assertThat(m).contains("'code'").contains("'customers'"));
    }

    @Test
    @DisplayName("$out and $merge are refused before anything reaches MongoDB")
    void writeStagesAreRefused() {
        assertThat(check("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$out\": \"copy\"}])"))
                .hasValueSatisfying(m -> assertThat(m).contains("never runs a write"));
        assertThat(check("db.orders.aggregate([{\"$merge\": {\"into\": \"copy\"}}])")).isPresent();
    }

    @Test
    @DisplayName("a path below a field declared as an untyped object is accepted")
    void belowAnObjectField() {
        activeSchema.publish(new SchemaSnapshot("u", "logs.json", "querysense_uploads",
                List.of(new KnownSchema.Table("logs", List.of("_id", "meta"))), List.of(), Map.of(),
                Instant.now(), Dialect.MONGODB, List.of(),
                Map.of("logs", Map.of("_id", "objectId", "meta", "object"))));

        assertThat(check("db.logs.find({\"meta.source\": \"web\"})")).isEmpty();
        assertThat(check("db.customers.find({})")).isPresent();
    }
}
