package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SchemaProvisionerTest
 *
 * touchesOnlyTheUploadCatalog is the safety test. This class is the only
 * one in the project that issues DDL, and the one mistake it must never
 * make is naming the connected demo database — which holds the built-in
 * nine tables and query_history.
 */
class SchemaProvisionerTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaProvisioner provisioner;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of());
        provisioner = new SchemaProvisioner(jdbcTemplate, "querysense_uploads");
    }

    @Test
    @DisplayName("creates the upload database and never drops a database")
    void createsUploadDatabaseOnly() {
        provisioner.provision(schema());

        List<String> executed = executedStatements();
        assertThat(executed).anyMatch(s -> s.startsWith("CREATE DATABASE IF NOT EXISTS `querysense_uploads`"));
        assertThat(executed).noneMatch(s -> s.toUpperCase().startsWith("DROP DATABASE"));
    }

    @Test
    @DisplayName("every statement that names a database names only the upload catalog")
    void touchesOnlyTheUploadCatalog() {
        provisioner.provision(schema());

        assertThat(executedStatements())
                .filteredOn(s -> s.contains("`querysense`"))
                .isEmpty();
    }

    @Test
    @DisplayName("emits a CREATE TABLE qualified to the upload catalog, with declared types")
    void emitsCreateTable() {
        provisioner.provision(schema());

        assertThat(executedStatements())
                .anyMatch(s -> s.contains("CREATE TABLE `querysense_uploads`.`customers`")
                        && s.contains("`id` INT NOT NULL AUTO_INCREMENT")
                        && s.contains("`name` VARCHAR(120)")
                        && s.contains("PRIMARY KEY (`id`)"));
    }

    @Test
    @DisplayName("adds foreign keys after every table exists, not inline")
    void addsForeignKeysAfterwards() {
        ParsedTable customers = new ParsedTable("customers", List.of(pk("id")), List.of());
        ParsedTable orders = new ParsedTable("orders",
                List.of(pk("id"), col("customer_id", "INT")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")));

        provisioner.provision(new ParsedSchema(List.of(customers, orders), List.of()));

        List<String> executed = executedStatements();
        int lastCreate = lastIndexMatching(executed, "CREATE TABLE");
        int addFk = lastIndexMatching(executed, "ADD CONSTRAINT");

        assertThat(addFk).isGreaterThan(lastCreate);
        assertThat(executed.get(addFk))
                .contains("FOREIGN KEY (`customer_id`) REFERENCES `querysense_uploads`.`customers` (`id`)");
    }

    @Test
    @DisplayName("disables and re-enables foreign key checks around the drop phase")
    void togglesForeignKeyChecks() {
        provisioner.provision(schema());

        assertThat(executedStatements()).contains("SET FOREIGN_KEY_CHECKS = 0", "SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("drops the tables already present in the upload catalog")
    void dropsExistingTables() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of("stale_table"));

        provisioner.provision(schema());

        assertThat(executedStatements()).contains("DROP TABLE IF EXISTS `querysense_uploads`.`stale_table`");
    }

    @Test
    @DisplayName("a VARCHAR with no declared length still gets one, since MySQL requires it")
    void suppliesDefaultVarcharLength() {
        ParsedTable t = new ParsedTable("notes",
                List.of(pk("id"), new ParsedColumn("body", "VARCHAR", List.of(), false, false, false, false)),
                List.of());

        provisioner.provision(new ParsedSchema(List.of(t), List.of()));

        assertThat(executedStatements()).anyMatch(s -> s.contains("`body` VARCHAR(255)"));
    }

    private List<String> executedStatements() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }

    private static int lastIndexMatching(List<String> statements, String fragment) {
        int found = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) {
                found = i;
            }
        }
        return found;
    }

    private static ParsedSchema schema() {
        return new ParsedSchema(List.of(new ParsedTable("customers",
                List.of(new ParsedColumn("id", "INT", List.of(), true, true, false, true),
                        new ParsedColumn("name", "VARCHAR", List.of("120"), false, false, false, false)),
                List.of())), List.of());
    }

    private static ParsedColumn pk(String name) {
        return new ParsedColumn(name, "INT", List.of(), true, true, false, true);
    }

    private static ParsedColumn col(String name, String type) {
        return new ParsedColumn(name, type, List.of(), false, false, false, false);
    }
}
