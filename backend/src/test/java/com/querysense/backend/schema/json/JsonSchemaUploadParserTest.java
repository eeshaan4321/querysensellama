package com.querysense.backend.schema.json;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaUploadParserTest {

    private final JsonSchemaUploadParser parser = new JsonSchemaUploadParser();

    private static final String SPEC_EXAMPLE = """
            {
              "collections": [{
                "name": "orders",
                "validator": { "$jsonSchema": {
                  "bsonType": "object",
                  "required": ["customerId", "status"],
                  "properties": {
                    "customerId": {"bsonType": "objectId"},
                    "status": {"bsonType": "string", "enum": ["PENDING", "SHIPPED"]},
                    "items": {"bsonType": "array", "items": {"bsonType": "object",
                      "properties": {"productId": {"bsonType": "objectId"},
                                     "quantity": {"bsonType": "int"}}}}
                  }}},
                "indexes": [{"key": {"customerId": 1}}, {"key": {"email": 1}, "unique": true}],
                "references": {"customerId": "customers._id", "items.productId": "products._id"}
              }]
            }
            """;

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(parser.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("the documented format: dotted paths, _id first, embedded arrays, types and required")
    void documentedFormat() {
        ParsedTable orders = parser.parse(SPEC_EXAMPLE).table("orders").orElseThrow();

        assertThat(orders.columnNames())
                .containsExactly("_id", "customerId", "status", "items.productId", "items.quantity");
        assertThat(orders.embeddedArrays()).containsExactly("items");
        assertThat(orders.column("_id").orElseThrow().type()).isEqualTo("objectId");
        assertThat(orders.column("items.quantity").orElseThrow().type()).isEqualTo("int");
        assertThat(orders.column("customerId").orElseThrow().notNull()).isTrue();
        assertThat(orders.column("items.productId").orElseThrow().notNull()).isFalse();
        assertThat(orders.validator().get("$jsonSchema")).isInstanceOf(Document.class);
    }

    @Test
    @DisplayName("references become foreign keys, and indexes keep their order, direction and uniqueness")
    void referencesAndIndexes() {
        ParsedSchema schema = parser.parse(SPEC_EXAMPLE);

        assertThat(schema.allForeignKeys()).containsExactly(
                new KnownSchema.ForeignKey("orders", "customerId", "customers", "_id"),
                new KnownSchema.ForeignKey("orders", "items.productId", "products", "_id"));
        assertThat(schema.indexes()).containsExactly(
                new IndexSpec("orders", List.of(new IndexSpec.KeyPart("customerId", 1)), false),
                new IndexSpec("orders", List.of(new IndexSpec.KeyPart("email", 1)), true));
    }

    @Test
    @DisplayName("a single-key unique index marks its field unique")
    void uniqueIndexMarksField() {
        ParsedTable customers = parser.parse("""
                {"collections": [{"name": "customers",
                  "validator": {"$jsonSchema": {"properties": {"email": {"bsonType": "string"}}}},
                  "indexes": [{"key": {"email": 1}, "unique": true}]}]}
                """).table("customers").orElseThrow();

        assertThat(customers.column("email").orElseThrow().unique()).isTrue();
    }

    @Test
    @DisplayName("getCollectionInfos output: views and system collections are skipped with a warning")
    void collectionInfosSkipViewsAndSystemCollections() {
        ParsedSchema schema = parser.parse("""
                [
                  {"name": "customers", "type": "collection",
                   "options": {"validator": {"$jsonSchema": {"properties": {"city": {"bsonType": "string"}}}}},
                   "info": {"readOnly": false}, "idIndex": {"v": 2, "key": {"_id": 1}, "name": "_id_"}},
                  {"name": "big_spenders", "type": "view", "options": {"viewOn": "customers", "pipeline": []}},
                  {"name": "system.views", "type": "collection", "options": {}}
                ]
                """);

        assertThat(schema.tables()).extracting(ParsedTable::name).containsExactly("customers");
        assertThat(schema.table("customers").orElseThrow().columnNames()).containsExactly("_id", "city");
        assertThat(schema.warnings()).hasSize(2)
                .anySatisfy(w -> assertThat(w).contains("big_spenders").contains("view"))
                .anySatisfy(w -> assertThat(w).contains("system.views"));
    }

    @Test
    @DisplayName("nested and scalar arrays: every array path is an embedded array, a scalar array is its own leaf")
    void nestedArrays() {
        ParsedTable orders = parser.parse("""
                {"collections": [{"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "tags": {"bsonType": "array", "items": {"bsonType": "string"}},
                  "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                    "sku": {"bsonType": "string"},
                    "options": {"bsonType": "array", "items": {"bsonType": "string"}}}}}}}}}]}
                """).table("orders").orElseThrow();

        assertThat(orders.columnNames()).containsExactly("_id", "tags", "items.sku", "items.options");
        assertThat(orders.embeddedArrays()).containsExactly("tags", "items", "items.options");
        assertThat(orders.column("tags").orElseThrow().type()).isEqualTo("string");
    }

    @Test
    @DisplayName("an object without bsonType but with properties is still walked")
    void untypedObjectIsWalked() {
        ParsedTable customers = parser.parse("""
                {"collections": [{"name": "customers", "validator": {"$jsonSchema": {"properties": {
                  "address": {"properties": {"city": {"bsonType": "string"}}}}}}}]}
                """).table("customers").orElseThrow();

        assertThat(customers.columnNames()).containsExactly("_id", "address.city");
    }

    @Test
    @DisplayName("a type list takes its first non-null type; JSON Schema's type names are normalised")
    void typeListsAndJsonTypes() {
        ParsedTable t = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "a": {"bsonType": ["null", "string"]},
                  "b": {"type": "boolean"},
                  "c": {}}}}}]}
                """).table("t").orElseThrow();

        assertThat(t.column("a").orElseThrow().type()).isEqualTo("string");
        assertThat(t.column("b").orElseThrow().type()).isEqualTo("bool");
        assertThat(t.column("c").orElseThrow().type()).isEqualTo("any");
    }

    @Test
    @DisplayName("maxLength becomes the column's length argument")
    void maxLengthIsKept() {
        ParsedColumn code = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "code": {"bsonType": "string", "maxLength": 12}}}}}]}
                """).table("t").orElseThrow().column("code").orElseThrow();

        assertThat(code.typeArgs()).containsExactly("12");
    }

    @Test
    @DisplayName("unread keywords such as oneOf warn rather than fail")
    void unreadKeywordsWarn() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "v": {"oneOf": [{"bsonType": "int"}, {"bsonType": "string"}]}}}}}]}
                """);

        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("oneOf").contains("v"));
    }

    @Test
    @DisplayName("constraints the seeder cannot follow warn up front")
    void ignoredConstraintsWarn() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "zip": {"bsonType": "string", "pattern": "^[0-9]{6}$"}}}}}]}
                """);

        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("t.zip").contains("pattern"));
    }

    @Test
    @DisplayName("an optional field of a type that cannot be generated warns; the field is still declared")
    void unsupportedOptionalTypeWarns() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "blob": {"bsonType": "binData"}}}}}]}
                """);

        assertThat(schema.table("t").orElseThrow().column("blob")).isPresent();
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("t.blob").contains("binData"));
    }

    @Test
    @DisplayName("a collection without a $jsonSchema knows only _id, and says so")
    void noValidator() {
        ParsedSchema schema = parser.parse("{\"collections\": [{\"name\": \"logs\"}]}");

        assertThat(schema.table("logs").orElseThrow().columnNames()).containsExactly("_id");
        assertThat(schema.table("logs").orElseThrow().validator()).isNull();
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("logs").contains("$jsonSchema"));
    }

    @Test
    @DisplayName("the _id index is implicit; a text index is skipped with a warning")
    void specialIndexes() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t",
                  "validator": {"$jsonSchema": {"properties": {"body": {"bsonType": "string"}}}},
                  "indexes": [{"key": {"_id": 1}}, {"key": {"body": "text"}}]}]}
                """);

        assertThat(schema.indexes()).isEmpty();
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("body").contains("t"));
    }

    @Test
    @DisplayName("mongosh-style unquoted keys and single quotes are accepted")
    void relaxedJson() {
        ParsedSchema schema = parser.parse("{collections: [{name: 'customers'}]}");

        assertThat(schema.tables()).extracting(ParsedTable::name).containsExactly("customers");
    }

    @Test
    @DisplayName("malformed JSON names the line and column")
    void malformedJsonNamesPosition() {
        String broken = "{\n  \"collections\": [\n    {\"name\": \"x\",, }\n  ]\n}";

        assertThatThrownBy(() -> parser.parse(broken))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("line 3");
    }

    @Test
    @DisplayName("JSON of the wrong shape says what shape is expected")
    void wrongShape() {
        assertThatThrownBy(() -> parser.parse("{\"tables\": []}"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("\"collections\"");
    }

    @Test
    @DisplayName("a collection without a name is refused")
    void missingName() {
        assertThatThrownBy(() -> parser.parse("{\"collections\": [{\"validator\": {}}]}"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a reference that is not collection.field is refused")
    void malformedReference() {
        assertThatThrownBy(() -> parser.parse("""
                {"collections": [{"name": "orders", "references": {"customer_id": "customers"}}]}
                """))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("orders.customer_id")
                .hasMessageContaining("customers._id");
    }
}
