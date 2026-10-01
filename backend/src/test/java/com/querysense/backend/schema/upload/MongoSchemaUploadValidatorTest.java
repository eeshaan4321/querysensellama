package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoSchemaUploadValidatorTest {

    private final JsonSchemaUploadParser parser = new JsonSchemaUploadParser();
    private final SchemaUploadValidator validator = new SchemaUploadValidator(40);

    private static final String CUSTOMERS = """
            {"name": "customers", "validator": {"$jsonSchema": {"properties": {
              "name": {"bsonType": "string"}, "email": {"bsonType": "string"}}}},
             "indexes": [{"key": {"email": 1}, "unique": true}]}
            """;

    private static final String ORDERS = """
            {"name": "orders", "validator": {"$jsonSchema": {"required": ["customer_id"], "properties": {
              "customer_id": {"bsonType": "objectId"},
              "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                "sku": {"bsonType": "string"}}}}}}},
             "indexes": [{"key": {"customer_id": 1}}, {"key": {"items": 1}}],
             "references": {"customer_id": "customers._id"}}
            """;

    private ParsedSchema schema(String... collections) {
        return parser.parse("{\"collections\": [" + String.join(",", collections) + "]}");
    }

    private void assertRefused(ParsedSchema schema, String... fragments) {
        var assertion = assertThatThrownBy(() -> validator.validate(schema, Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class);
        for (String fragment : fragments) {
            assertion.hasMessageContaining(fragment);
        }
    }

    @Test
    @DisplayName("a well-formed schema with references and indexes passes")
    void validSchemaPasses() {
        assertThatCode(() -> validator.validate(schema(CUSTOMERS, ORDERS), Dialect.MONGODB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no collections says what to upload")
    void emptyIsRefused() {
        assertRefused(parser.parse("{\"collections\": []}"), "No collections", "$jsonSchema");
    }

    @Test
    @DisplayName("more collections than the cap are refused")
    void capIsEnforced() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 41; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\": \"c").append(i).append("\"}");
        }
        assertRefused(schema(many.toString()), "41 collections", "40");
    }

    @Test
    @DisplayName("an unsafe collection name is refused")
    void unsafeCollectionName() {
        assertRefused(schema("{\"name\": \"my-coll\"}"), "Collection name", "my-coll");
    }

    @Test
    @DisplayName("an unsafe segment in a dotted field path is refused")
    void unsafeFieldSegment() {
        assertRefused(schema("""
                {"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "a": {"properties": {"bad field": {"bsonType": "string"}}}}}}}
                """), "Field name", "bad field");
    }

    @Test
    @DisplayName("a collection declared twice is refused")
    void duplicateCollection() {
        assertRefused(schema(CUSTOMERS, CUSTOMERS), "more than once", "customers");
    }

    @Test
    @DisplayName("a reference from an undeclared field is refused")
    void referenceSourceUndeclared() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "references": {"customer_id": "customers._id"}}
                """), "orders.customer_id", "not declared");
    }

    @Test
    @DisplayName("a reference to a missing collection is refused")
    void referenceTargetCollectionMissing() {
        assertRefused(schema(ORDERS), "customers", "not in the uploaded schema");
    }

    @Test
    @DisplayName("a reference to an undeclared target field is refused")
    void referenceTargetFieldMissing() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "customer_code": {"bsonType": "string"}}}},
                 "references": {"customer_code": "customers.code"}}
                """), "customers.code", "not declared");
    }

    @Test
    @DisplayName("a reference into an embedded array is refused: the target must have one value per document")
    void referenceIntoArray() {
        assertRefused(schema(ORDERS.replace("customers._id", "customers._id"), CUSTOMERS, """
                {"name": "returns", "validator": {"$jsonSchema": {"properties": {
                  "sku": {"bsonType": "string"}}}},
                 "references": {"sku": "orders.items.sku"}}
                """), "orders.items.sku", "embedded array");
    }

    @Test
    @DisplayName("a reference whose bsonType differs from its target's is refused, naming both types")
    void referenceTypeMismatchIsRefused() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "customer_id": {"bsonType": "int"}}}},
                 "references": {"customer_id": "customers._id"}}
                """), "orders.customer_id", "int", "objectId");
    }

    @Test
    @DisplayName("an index on an undeclared field is refused (the spec's own example indexes email on orders)")
    void indexOnUndeclaredField() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "status": {"bsonType": "string"}}}},
                 "indexes": [{"key": {"email": 1}, "unique": true}]}
                """), "orders", "email", "not declared");
    }

    @Test
    @DisplayName("a required field of a type that cannot be generated is refused")
    void requiredUnsupportedType() {
        assertRefused(schema("""
                {"name": "t", "validator": {"$jsonSchema": {"required": ["blob"], "properties": {
                  "blob": {"bsonType": "binData"}}}}}
                """), "t.blob", "binData", "required");
    }

    @Test
    @DisplayName("additionalProperties: false without _id in properties is refused, saying how to fix it")
    void closedSchemaWithoutIdIsRefused() {
        assertRefused(schema("""
                {"name": "t", "validator": {"$jsonSchema": {"additionalProperties": false, "properties": {
                  "name": {"bsonType": "string"}}}}}
                """), "additionalProperties", "_id");
    }

    @Test
    @DisplayName("additionalProperties: false with _id declared passes")
    void closedSchemaWithIdPasses() {
        ParsedSchema closed = schema("""
                {"name": "t", "validator": {"$jsonSchema": {"additionalProperties": false, "properties": {
                  "_id": {"bsonType": "objectId"}, "name": {"bsonType": "string"}}}}}
                """);

        assertThatCode(() -> validator.validate(closed, Dialect.MONGODB)).doesNotThrowAnyException();
    }
}
