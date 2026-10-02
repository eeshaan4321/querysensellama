package com.querysense.backend.schema.upload;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoSeederTest {

    private static final String SHOP = """
            {"collections": [
              {"name": "customers", "validator": {"$jsonSchema": {"required": ["name"], "properties": {
                 "name": {"bsonType": "string"}, "email": {"bsonType": "string"}, "city": {"bsonType": "string"},
                 "code": {"bsonType": "string", "maxLength": 6}}}},
               "indexes": [{"key": {"email": 1}, "unique": true}]},
              {"name": "products", "validator": {"$jsonSchema": {"required": ["price"], "properties": {
                 "price": {"bsonType": "decimal"}, "stock": {"bsonType": "int"}, "active": {"bsonType": "bool"}}}}},
              {"name": "employees", "validator": {"$jsonSchema": {"properties": {
                 "manager_id": {"bsonType": "objectId"}}}},
               "references": {"manager_id": "employees._id"}},
              {"name": "orders", "validator": {"$jsonSchema": {
                 "required": ["customer_id", "status", "order_date", "items"], "properties": {
                 "customer_id": {"bsonType": "objectId"},
                 "status": {"bsonType": "string", "enum": ["NEW", "SHIPPED"]},
                 "order_date": {"bsonType": "date"},
                 "blob": {"bsonType": "binData"},
                 "items": {"bsonType": "array", "items": {"bsonType": "object",
                   "required": ["product_id", "quantity"], "properties": {
                   "product_id": {"bsonType": "objectId"}, "quantity": {"bsonType": "int"}}}}}}},
               "references": {"customer_id": "customers._id", "items.product_id": "products._id"}}
            ]}
            """;

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private ParsedSchema schema;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection(anyString())).thenReturn(collection);
        schema = new JsonSchemaUploadParser().parse(SHOP);
    }

    private MongoSeeder seeder(int rows) {
        return new MongoSeeder(client, "querysense_uploads", rows, 20260910L);
    }

    private static Set<Object> ids(List<Document> documents) {
        return documents.stream().map(d -> d.get("_id")).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(seeder(1).dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("every collection gets seed-rows documents with distinct ObjectIds")
    void rowsAndIds() {
        Map<String, List<Document>> docs = seeder(50).generate(schema);

        assertThat(docs).containsOnlyKeys("customers", "products", "employees", "orders");
        docs.values().forEach(list -> {
            assertThat(list).hasSize(50);
            assertThat(ids(list)).hasSize(50).allSatisfy(id -> assertThat(id).isInstanceOf(ObjectId.class));
        });
    }

    @Test
    @DisplayName("required fields are always present, and values have the BSON types the validator demands")
    void typesAndRequired() {
        Map<String, List<Document>> docs = seeder(50).generate(schema);

        assertThat(docs.get("orders")).allSatisfy(o -> {
            assertThat(o.get("customer_id")).isInstanceOf(ObjectId.class);
            assertThat(o.getString("status")).isIn("NEW", "SHIPPED");
            assertThat(o.get("order_date")).isExactlyInstanceOf(Date.class);
            assertThat(o).doesNotContainKey("blob");
        });
        assertThat(docs.get("products")).allSatisfy(p -> assertThat(p.get("price")).isInstanceOf(Decimal128.class));
        assertThat(docs.get("products")).filteredOn(p -> p.containsKey("stock"))
                .allSatisfy(p -> assertThat(p.get("stock")).isInstanceOf(Integer.class));
        assertThat(docs.get("products")).filteredOn(p -> p.containsKey("active"))
                .allSatisfy(p -> assertThat(p.get("active")).isInstanceOf(Boolean.class));
        assertThat(docs.get("customers")).allSatisfy(c -> assertThat(c.getString("name")).isNotBlank());
    }

    @Test
    @DisplayName("an optional field is sometimes left out")
    void optionalFieldsAreSometimesOmitted() {
        List<Document> customers = seeder(200).generate(schema).get("customers");

        assertThat(customers).anySatisfy(c -> assertThat(c).doesNotContainKey("city"));
        assertThat(customers).anySatisfy(c -> assertThat(c).containsKey("city"));
    }

    @Test
    @DisplayName("an optional field with a unique index is in every document, with distinct values")
    void uniqueOptionalFieldIsAlwaysPresent() {
        List<Document> customers = seeder(200).generate(schema).get("customers");

        assertThat(customers).allSatisfy(c -> assertThat(c).containsKey("email"));
        assertThat(customers.stream().map(c -> c.get("email")).collect(Collectors.toSet())).hasSize(200);
    }

    @Test
    @DisplayName("maxLength is honoured")
    void maxLengthIsHonoured() {
        assertThat(seeder(100).generate(schema).get("customers"))
                .filteredOn(c -> c.containsKey("code"))
                .allSatisfy(c -> assertThat(c.getString("code").length()).isLessThanOrEqualTo(6));
    }

    @Test
    @DisplayName("embedded arrays get 1-4 elements, each with its required fields")
    void embeddedArrays() {
        assertThat(seeder(100).generate(schema).get("orders")).allSatisfy(o -> {
            List<?> items = o.getList("items", Object.class);
            assertThat(items).hasSizeBetween(1, 4);
            assertThat(items).allSatisfy(i -> {
                assertThat(((Document) i).get("product_id")).isInstanceOf(ObjectId.class);
                assertThat(((Document) i).get("quantity")).isInstanceOf(Integer.class);
            });
        });
    }

    @Test
    @DisplayName("references draw from the target's _ids, through arrays and onto the same collection")
    void referencesPointAtSeededIds() {
        Map<String, List<Document>> docs = seeder(80).generate(schema);
        Set<Object> customerIds = ids(docs.get("customers"));
        Set<Object> productIds = ids(docs.get("products"));
        Set<Object> employeeIds = ids(docs.get("employees"));

        assertThat(docs.get("orders")).allSatisfy(o -> {
            assertThat(customerIds).contains(o.get("customer_id"));
            o.getList("items", Document.class)
                    .forEach(i -> assertThat(productIds).contains(i.get("product_id")));
        });
        assertThat(docs.get("employees")).filteredOn(e -> e.containsKey("manager_id"))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(employeeIds).contains(e.get("manager_id")));
    }

    @Test
    @DisplayName("the same seed generates the same documents")
    void deterministic() {
        assertThat(seeder(30).generate(schema)).isEqualTo(seeder(30).generate(schema));
    }

    @Test
    @DisplayName("documents go in unordered batches of 1,000, and the report counts them")
    @SuppressWarnings("unchecked")
    void insertsInBatches() {
        ParsedSchema one = new JsonSchemaUploadParser().parse("{\"collections\": [{\"name\": \"logs\"}]}");

        SeedReport report = seeder(2500).seed(one);

        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<InsertManyOptions> options = ArgumentCaptor.forClass(InsertManyOptions.class);
        verify(collection, times(3)).insertMany(batches.capture(), options.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(1000, 1000, 500);
        assertThat(options.getAllValues()).allSatisfy(o -> assertThat(o.isOrdered()).isFalse());
        assertThat(report.rowCounts()).containsEntry("logs", 2500);
        assertThat(report.warnings()).isEmpty();
        verify(client).getDatabase("querysense_uploads");
    }

    @Test
    @DisplayName("a validation failure fails the upload, naming the collection and the field")
    void validationFailureNamesCollectionAndField() {
        BsonDocument details = new BsonDocument("failingDocumentId", new BsonString("x"))
                .append("details", new BsonDocument("schemaRulesNotSatisfied", new BsonArray(List.of(
                        new BsonDocument("operatorName", new BsonString("properties"))
                                .append("propertiesNotSatisfied", new BsonArray(List.of(
                                        new BsonDocument("propertyName", new BsonString("zip")))))))));
        BulkWriteError error = new BulkWriteError(121, "Document failed validation", details, 0);
        doThrow(new MongoBulkWriteException(mock(BulkWriteResult.class), List.of(error), null,
                new ServerAddress(), Set.of()))
                .when(collection).insertMany(anyList(), any(InsertManyOptions.class));

        assertThatThrownBy(() -> seeder(10).seed(schema))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("'customers'")
                .hasMessageContaining("'zip'");
    }

    @Test
    @DisplayName("missingProperties is found as the offending field too")
    void missingPropertiesIsNamed() {
        BsonDocument details = new BsonDocument("details", new BsonDocument("schemaRulesNotSatisfied",
                new BsonArray(List.of(new BsonDocument("operatorName", new BsonString("required"))
                        .append("missingProperties", new BsonArray(List.of(new BsonString("sku"))))))));

        assertThat(MongoSeeder.offendingField(details)).isEqualTo("sku");
        assertThat(MongoSeeder.offendingField(new BsonDocument())).isNull();
    }

    @Test
    @DisplayName("duplicate-key rejections become a warning, with the inserted count from MongoDB")
    void duplicatesWarn() {
        BulkWriteResult result = mock(BulkWriteResult.class);
        when(result.getInsertedCount()).thenReturn(8);
        BulkWriteError duplicate = new BulkWriteError(11000, "E11000 duplicate key error", new BsonDocument(), 3);
        doThrow(new MongoBulkWriteException(result, List.of(duplicate, duplicate), null, new ServerAddress(), Set.of()))
                .when(collection).insertMany(anyList(), any(InsertManyOptions.class));
        ParsedSchema one = new JsonSchemaUploadParser().parse("{\"collections\": [{\"name\": \"logs\"}]}");

        SeedReport report = seeder(10).seed(one);

        assertThat(report.rowCounts()).containsEntry("logs", 8);
        assertThat(report.warnings()).singleElement()
                .satisfies(w -> assertThat(w).contains("8 of 10").contains("logs").contains("E11000"));
    }

    @Test
    @DisplayName("losing the server mid-seed is a MongoUnavailableException (a 503)")
    void serverLostWhileSeedingIs503() {
        doThrow(new MongoTimeoutException("Timed out after 2000 ms"))
                .when(collection).insertMany(anyList(), any(InsertManyOptions.class));

        assertThatThrownBy(() -> seeder(10).seed(schema)).isInstanceOf(MongoUnavailableException.class);
    }

    @Test
    @DisplayName("only the referenced fields are held in memory: one pool per target, the target's own values")
    void referencePoolsHoldOnlyReferencedFields() {
        Map<String, List<Document>> docs = seeder(80).generate(schema);

        Map<String, List<Object>> pools = seeder(80).referencePools(schema);

        assertThat(pools).containsOnlyKeys("customers._id", "products._id", "employees._id");
        assertThat(pools.get("customers._id")).hasSize(80).containsExactlyElementsOf(
                docs.get("customers").stream().map(d -> d.get("_id")).toList());
    }

    @Test
    @DisplayName("seeding streams each collection in batches and inserts exactly what generate() describes")
    @SuppressWarnings("unchecked")
    void streamedSeedMatchesGenerated() {
        Map<String, List<Document>> inserted = new java.util.LinkedHashMap<>();
        when(database.getCollection(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            MongoCollection<Document> target = mock(MongoCollection.class);
            when(target.insertMany(anyList(), any(InsertManyOptions.class))).thenAnswer(call -> {
                inserted.computeIfAbsent(name, k -> new java.util.ArrayList<>()).addAll(call.getArgument(0));
                return null;
            });
            return target;
        });

        seeder(1500).seed(schema);

        assertThat(inserted).isEqualTo(seeder(1500).generate(schema));
    }
}
