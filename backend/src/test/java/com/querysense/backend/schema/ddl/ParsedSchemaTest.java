package com.querysense.backend.schema.ddl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.upload.SchemaProvisioner;
import com.querysense.backend.schema.upload.SchemaSeeder;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ParsedSchemaTest {

    private static ParsedColumn column(String name, String type) {
        return new ParsedColumn(name, type, List.of(), false, false, false, false);
    }

    @Test
    @DisplayName("field types map each table's columns to their declared type, in declaration order")
    void fieldTypesFollowDeclarations() {
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("_id", "objectId"), column("items.quantity", "int")),
                List.of(), List.of("items"), new Document("$jsonSchema", new Document()));
        ParsedSchema schema = new ParsedSchema(List.of(orders), List.of(), List.of());

        assertThat(schema.fieldTypes()).containsOnlyKeys("orders");
        assertThat(schema.fieldTypes().get("orders"))
                .containsExactly(Map.entry("_id", "objectId"), Map.entry("items.quantity", "int"));
    }

    @Test
    @DisplayName("indexesOf returns only the named table's indexes, ignoring case")
    void indexesOfFiltersByTable() {
        IndexSpec email = new IndexSpec("customers", List.of(new IndexSpec.KeyPart("email", 1)), true);
        IndexSpec status = new IndexSpec("orders", List.of(new IndexSpec.KeyPart("status", 1)), false);
        ParsedSchema schema = new ParsedSchema(List.of(), List.of(), List.of(email, status));

        assertThat(schema.indexesOf("CUSTOMERS")).containsExactly(email);
    }

    @Test
    @DisplayName("the SQL-shaped constructors keep meaning what they meant")
    void sqlConstructorsDefaultTheNewParts() {
        ParsedTable table = new ParsedTable("customers", List.of(column("id", "INT")), List.of());
        ParsedSchema schema = new ParsedSchema(List.of(table), List.of());

        assertThat(table.embeddedArrays()).isEmpty();
        assertThat(table.validator()).isNull();
        assertThat(schema.indexes()).isEmpty();
        assertThat(table.toKnownTable()).isEqualTo(new KnownSchema.Table("customers", List.of("id")));
    }

    @Test
    @DisplayName("toKnownTable carries embedded arrays")
    void knownTableCarriesEmbeddedArrays() {
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("_id", "objectId"), column("items.quantity", "int")),
                List.of(), List.of("items"), null);

        assertThat(orders.toKnownTable().embeddedArrays()).containsExactly("items");
    }

    @Test
    @DisplayName("older SchemaSnapshot constructors have no field types")
    void snapshotDefaultsFieldTypes() {
        SchemaSnapshot snapshot = new SchemaSnapshot("x", "x", null, List.of(), List.of(), Map.of(), Instant.EPOCH);

        assertThat(snapshot.fieldTypes()).isEmpty();
        assertThat(snapshot.fieldTypesFor("customers")).isEmpty();
    }

    @Test
    @DisplayName("the SQL upload strategies serve MySQL")
    void sqlStrategiesAreMySql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new DdlSchemaParser().dialect()).isEqualTo(Dialect.MYSQL);
        assertThat(new SchemaProvisioner(jdbc, "querysense_uploads").dialect()).isEqualTo(Dialect.MYSQL);
        assertThat(new SchemaProvisioner(jdbc, "querysense_uploads").uploadDatabase()).isEqualTo("querysense_uploads");
        assertThat(new SchemaSeeder(jdbc, "querysense_uploads", 10, 1L).dialect()).isEqualTo(Dialect.MYSQL);
    }
}
