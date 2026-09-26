package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.DdlSchemaParser;
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
    private ActiveSchemaProvider activeSchema;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        provisioner = mock(SchemaProvisioner.class);
        when(provisioner.uploadCatalog()).thenReturn("querysense_uploads");
        seeder = mock(SchemaSeeder.class);
        when(seeder.seed(any())).thenReturn(new SchemaSeeder.SeedReport(
                Map.of("customers", 500, "orders", 500), List.of()));
        activeSchema = new ActiveSchemaProvider();
        environment = new MockEnvironment();
    }

    private SchemaUploadService service() {
        return new SchemaUploadService(new DdlSchemaParser(), new SchemaUploadValidator(40),
                provisioner, seeder, activeSchema, environment);
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

        SchemaSnapshot snapshot = service.reset();

        assertThat(snapshot.builtIn()).isTrue();
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isTrue();
    }

    @Test
    @DisplayName("parser warnings survive the upload and are readable afterwards")
    void keepsWarnings() {
        SchemaUploadService service = service();
        service.upload(DDL + "\nCREATE TABLE broken (((;\n", "shop.sql");

        assertThat(service.lastWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("a blank label falls back to something displayable")
    void suppliesDefaultLabel() {
        assertThat(service().upload(DDL, "  ").label()).isNotBlank();
    }
}
