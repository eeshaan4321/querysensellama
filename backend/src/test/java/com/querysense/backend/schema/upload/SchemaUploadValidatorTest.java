package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaUploadValidatorTest {

    private SchemaUploadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaUploadValidator(40);
    }

    @Test
    @DisplayName("accepts a well-formed schema")
    void acceptsValidSchema() {
        assertThatCode(() -> validator.validate(schemaWith(table("customers", "id", "name"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a schema with no tables, naming what was expected")
    void rejectsEmptySchema() {
        assertThatThrownBy(() -> validator.validate(new ParsedSchema(List.of(), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("CREATE TABLE");
    }

    @Test
    @DisplayName("rejects more tables than the configured cap")
    void rejectsTooManyTables() {
        List<ParsedTable> many = IntStream.range(0, 41).mapToObj(i -> table("t" + i, "id")).toList();

        assertThatThrownBy(() -> validator.validate(new ParsedSchema(many, List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("40");
    }

    @Test
    @DisplayName("rejects a table name outside [A-Za-z0-9_]")
    void rejectsBadTableName() {
        assertThatThrownBy(() -> validator.validate(schemaWith(table("bad-name", "id"))))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("bad-name");
    }

    @Test
    @DisplayName("rejects a column name outside [A-Za-z0-9_]")
    void rejectsBadColumnName() {
        assertThatThrownBy(() -> validator.validate(schemaWith(table("customers", "id", "first name"))))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("first name");
    }

    @Test
    @DisplayName("rejects a table with no columns")
    void rejectsColumnlessTable() {
        assertThatThrownBy(() -> validator.validate(schemaWith(table("customers"))))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers");
    }

    @Test
    @DisplayName("rejects a foreign key pointing at a table that is not in the upload")
    void rejectsUnresolvableForeignKeyTable() {
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("id"), column("customer_id")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")));

        assertThatThrownBy(() -> validator.validate(new ParsedSchema(List.of(orders), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers");
    }

    @Test
    @DisplayName("rejects a foreign key pointing at a column that does not exist")
    void rejectsUnresolvableForeignKeyColumn() {
        ParsedTable customers = table("customers", "id");
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("id"), column("customer_id")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "missing")));

        assertThatThrownBy(() -> validator.validate(new ParsedSchema(List.of(customers, orders), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("rejects two tables with the same name")
    void rejectsDuplicateTables() {
        assertThatThrownBy(() -> validator.validate(
                new ParsedSchema(List.of(table("customers", "id"), table("customers", "id")), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers");
    }

    @Test
    @DisplayName("a SQL-injection-shaped identifier is refused, not escaped")
    void rejectsInjectionShapedIdentifier() {
        assertThatThrownBy(() -> validator.validate(
                schemaWith(table("users`; DROP DATABASE querysense; --", "id"))))
                .isInstanceOf(SchemaUploadException.class);
    }

    private static ParsedSchema schemaWith(ParsedTable... tables) {
        return new ParsedSchema(List.of(tables), List.of());
    }

    private static ParsedTable table(String name, String... columns) {
        return new ParsedTable(name,
                Arrays.stream(columns).map(SchemaUploadValidatorTest::column).toList(),
                List.of());
    }

    private static ParsedColumn column(String name) {
        return new ParsedColumn(name, "INT", List.of(), false, false, false, false);
    }
}
