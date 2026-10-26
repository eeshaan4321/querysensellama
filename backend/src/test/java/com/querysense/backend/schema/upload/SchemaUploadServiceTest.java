package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.DdlSchemaParser;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaUploadServiceTest {

    private static final String DDL = """
            CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(80));
            CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT,
              CONSTRAINT fk FOREIGN KEY (customer_id) REFERENCES customers (id));
            """;

    private SchemaProvisioner provisioner;
    private SchemaSeeder seeder;
    private SchemaProvisioning mongoProvisioner;
    private SchemaSeeding mongoSeeder;
    private ActiveSchemaProvider activeSchema;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        provisioner = mock(SchemaProvisioner.class);
        when(provisioner.uploadCatalog()).thenReturn("querysense_uploads");
        when(provisioner.uploadDatabase()).thenReturn("querysense_uploads");
        when(provisioner.dialect()).thenReturn(Dialect.MYSQL);
        seeder = mock(SchemaSeeder.class);
        when(seeder.dialect()).thenReturn(Dialect.MYSQL);
        when(seeder.seed(any())).thenReturn(new SeedReport(
                Map.of("customers", 500, "orders", 500), List.of()));
        activeSchema = new ActiveSchemaProvider();
        environment = new MockEnvironment();
        mongoProvisioner = mock(SchemaProvisioning.class);
        when(mongoProvisioner.dialect()).thenReturn(Dialect.MONGODB);
        when(mongoProvisioner.uploadDatabase()).thenReturn("querysense_uploads");
        mongoSeeder = mock(SchemaSeeding.class);
        when(mongoSeeder.dialect()).thenReturn(Dialect.MONGODB);
        when(mongoSeeder.seed(any())).thenReturn(new SeedReport(Map.of("orders", 12), List.of("seed note")));
        environment.setProperty("spring.data.mongodb.uri", "mongodb://localhost:27017/querysense");
    }

    private SchemaUploadService service() {
        return new SchemaUploadService(
                List.of(new DdlSchemaParser(), new JsonSchemaUploadParser()),
                new SchemaUploadValidator(40),
                List.of(provisioner, mongoProvisioner),
                List.of(seeder, mongoSeeder),
                activeSchema, environment);
    }

    @Test
    @DisplayName("publishes the uploaded schema with its catalog, tables and row counts")
    void publishesUploadedSchema() {
        SchemaSnapshot snapshot = service().upload(DDL, "shop.sql");

        assertThat(snapshot.builtIn()).isFalse();
        assertThat(snapshot.label()).isEqualTo("shop.sql");
        assertThat(snapshot.catalog()).isEqualTo("querysense_uploads");
        assertThat(snapshot.tableNames()).containsExactly("customers", "orders");
        assertThat(snapshot.rowCountFor("customers")).isEqualTo(500);
        assertThat(snapshot.foreignKeys()).hasSize(1);
        assertThat(activeSchema.active(Dialect.MYSQL)).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("provisions before seeding")
    void provisionsThenSeeds() {
        service().upload(DDL, "shop.sql");

        var order = inOrder(provisioner, seeder);
        order.verify(provisioner).provision(any());
        order.verify(seeder).seed(any());
        order.verify(provisioner).finish(any());
    }

    @Test
    @DisplayName("a foreign key that could not be added after seeding is reported with the upload")
    void finishWarningsAreKept() {
        when(provisioner.finish(any())).thenReturn(List.of("The foreign key orders.customer_id -> customers.id could not be added"));

        SchemaUploadService service = service();
        service.upload(DDL, "shop.sql");

        assertThat(service.lastWarnings(Dialect.MYSQL))
                .containsExactly("The foreign key orders.customer_id -> customers.id could not be added");
    }

    @Test
    @DisplayName("refuses under the h2 profile without touching the database")
    void refusesUnderH2() {
        environment.setActiveProfiles("h2");

        assertThatThrownBy(() -> service().upload(DDL, "shop.sql"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("H2");

        verify(provisioner, never()).provision(any());
        verify(seeder, never()).seed(any());
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isTrue();
    }

    @Test
    @DisplayName("an invalid schema is rejected before anything is provisioned")
    void validatesBeforeProvisioning() {
        assertThatThrownBy(() -> service().upload("SELECT 1;", "not-a-schema.sql"))
                .isInstanceOf(SchemaUploadException.class);

        verify(provisioner, never()).provision(any());
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isTrue();
    }

    @Test
    @DisplayName("blank DDL is rejected with a message that says what to upload")
    void rejectsBlankDdl() {
        assertThatThrownBy(() -> service().upload("   ", "empty.sql"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("reset returns to the built-in schema")
    void resetRestoresBuiltIn() {
        SchemaUploadService service = service();
        service.upload(DDL, "shop.sql");

        SchemaSnapshot snapshot = service.reset(Dialect.MYSQL);

        assertThat(snapshot.builtIn()).isTrue();
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isTrue();
    }

    @Test
    @DisplayName("parser warnings survive the upload and are readable afterwards")
    void keepsWarnings() {
        SchemaUploadService service = service();
        service.upload(DDL + "\nCREATE TABLE broken (((;\n", "shop.sql");

        assertThat(service.lastWarnings(Dialect.MYSQL)).isNotEmpty();
    }

    @Test
    @DisplayName("a blank label falls back to something displayable")
    void suppliesDefaultLabel() {
        assertThat(service().upload(DDL, "  ").label()).isNotBlank();
    }

    private static final String MONGO = """
            {"collections": [
              {"name": "customers", "validator": {"$jsonSchema": {"properties": {"email": {"bsonType": "string"}}}},
               "indexes": [{"key": {"email": 1}, "unique": true}]},
              {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                "customer_id": {"bsonType": "objectId"},
                "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                  "quantity": {"bsonType": "int"}}}}}}},
               "references": {"customer_id": "customers._id"}}]}
            """;

    @Test
    @DisplayName("a MongoDB upload goes through the Mongo parser, provisioner and seeder, and fills only the Mongo slot")
    void mongoUploadUsesMongoPipeline() {
        SchemaSnapshot snapshot = service().upload(MONGO, "shop.json", Dialect.MONGODB);

        verify(mongoProvisioner).provision(any());
        verify(mongoSeeder).seed(any());
        verify(provisioner, never()).provision(any());
        assertThat(snapshot.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(snapshot.catalog()).isEqualTo("querysense_uploads");
        assertThat(snapshot.tableNames()).containsExactly("customers", "orders");
        assertThat(snapshot.tables().get(1).embeddedArrays()).containsExactly("items");
        assertThat(snapshot.indexes()).singleElement().satisfies(i -> assertThat(i.unique()).isTrue());
        assertThat(snapshot.fieldTypesFor("orders")).containsEntry("items.quantity", "int");
        assertThat(snapshot.rowCountFor("orders")).isEqualTo(12);
        assertThat(snapshot.foreignKeys()).hasSize(1);
        assertThat(activeSchema.active(Dialect.MONGODB)).isEqualTo(snapshot);
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isTrue();
    }

    @Test
    @DisplayName("warnings are kept per dialect")
    void warningsPerDialect() {
        SchemaUploadService service = service();
        service.upload(MONGO, "shop.json", Dialect.MONGODB);

        assertThat(service.lastWarnings(Dialect.MONGODB)).contains("seed note");
        assertThat(service.lastWarnings(Dialect.MYSQL)).isEmpty();
    }

    @Test
    @DisplayName("a MongoDB upload without spring.data.mongodb.uri is refused before anything is touched")
    void mongoNeedsUri() {
        environment.setProperty("spring.data.mongodb.uri", "");

        assertThatThrownBy(() -> service().upload(MONGO, "shop.json", Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("spring.data.mongodb.uri");
        verify(mongoProvisioner, never()).provision(any());
    }

    @Test
    @DisplayName("the H2 profile refuses MySQL uploads only; a MongoDB upload does not touch MySQL")
    void h2AllowsMongo() {
        environment.setActiveProfiles("h2");

        service().upload(MONGO, "shop.json", Dialect.MONGODB);

        verify(mongoProvisioner).provision(any());
    }

    @Test
    @DisplayName("an invalid MongoDB schema is rejected before provisioning")
    void mongoValidatesBeforeProvisioning() {
        assertThatThrownBy(() -> service().upload("{\"collections\": []}", "empty.json", Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("No collections");
        verify(mongoProvisioner, never()).provision(any());
    }

    @Test
    @DisplayName("blank MongoDB text says to upload a .json file")
    void mongoBlank() {
        assertThatThrownBy(() -> service().upload("  ", "x.json", Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining(".json");
    }

    @Test
    @DisplayName("reset of MongoDB restores the Mongo built-in and leaves MySQL alone")
    void resetMongoOnly() {
        SchemaUploadService service = service();
        service.upload(DDL, "shop.sql");
        service.upload(MONGO, "shop.json", Dialect.MONGODB);

        SchemaSnapshot snapshot = service.reset(Dialect.MONGODB);

        assertThat(snapshot.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(activeSchema.isBuiltIn(Dialect.MONGODB)).isTrue();
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isFalse();
        assertThat(service.lastWarnings(Dialect.MONGODB)).isEmpty();
    }

    @Test
    @DisplayName("declared aliases are published, and a colliding one is dropped with a warning")
    void aliasesArePublished() {
        SchemaUploadService uploader = service();
        SchemaSnapshot snapshot = uploader.upload("""
                CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(80) COMMENT 'aka: label')
                  COMMENT 'aka: clients, orders';
                CREATE TABLE orders (id INT PRIMARY KEY);
                """, "shop.sql");

        assertThat(snapshot.aliasesFor("customers").table()).containsExactly("clients");
        assertThat(snapshot.aliasesFor("customers").column("name")).containsExactly("label");
        assertThat(uploader.lastWarnings(Dialect.MYSQL))
                .anySatisfy(w -> assertThat(w).contains("\"orders\"").contains("already the name of a table"));
    }
}
