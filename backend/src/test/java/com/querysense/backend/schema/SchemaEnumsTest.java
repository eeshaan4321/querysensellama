package com.querysense.backend.schema;

import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaEnumsTest {

    @Test
    @DisplayName("enums are read from $jsonSchema, including inside embedded arrays, as strings in order")
    void enumsFromValidators() {
        ParsedSchema schema = new JsonSchemaUploadParser().parse("""
                {"collections": [
                  {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                    "status": {"bsonType": "string", "enum": ["NEW", "PAID"]},
                    "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                      "size": {"bsonType": "int", "enum": [1, 2, 3]}}}}}}}},
                  {"name": "logs"}]}
                """);

        assertThat(schema.enums()).containsOnlyKeys("orders");
        assertThat(schema.enums().get("orders")).containsExactly(
                Map.entry("status", List.of("NEW", "PAID")),
                Map.entry("items.size", List.of("1", "2", "3")));
    }

    @Test
    @DisplayName("older snapshot constructors carry no enums")
    void olderConstructorsHaveNone() {
        SchemaSnapshot snapshot = new SchemaSnapshot("x", "x", null, List.of(), List.of(), Map.of(), Instant.EPOCH);

        assertThat(snapshot.enums()).isEmpty();
        assertThat(snapshot.enumsFor("orders")).isEmpty();
    }

    @Test
    @DisplayName("the built-in Mongo demo carries its enums")
    void builtInCarriesEnums() {
        assertThat(KnownMongoSchema.builtIn().enumsFor("orders"))
                .containsEntry("status", List.of("NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED"));
    }
}
