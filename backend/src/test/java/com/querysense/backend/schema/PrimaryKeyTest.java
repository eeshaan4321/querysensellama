package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrimaryKeyTest {

    private static final KnownSchema.Table DEPARTMENTS = new KnownSchema.Table("departments",
            List.of("department_name", "department_id"));
    private static final KnownSchema.Table STUDENTS = new KnownSchema.Table("students",
            List.of("student_name", "student_id", "department_id"));

    private static SchemaSnapshot snapshot(Dialect dialect, Map<String, String> keys,
                                           List<KnownSchema.ForeignKey> fks, KnownSchema.Table... tables) {
        return new SchemaSnapshot("upload-k", "k", "querysense_uploads", List.of(tables), fks, Map.of(),
                Instant.now(), dialect, List.of(), Map.of(), Map.of(), Map.of(), keys);
    }

    @Test
    @DisplayName("a declared key wins, even when it isn't the first column")
    void declared() {
        assertThat(snapshot(Dialect.MYSQL, Map.of("students", "student_id"), List.of(), STUDENTS)
                .primaryKeyFor(STUDENTS)).isEqualTo("student_id");
    }

    @Test
    @DisplayName("without a declared key, the column foreign keys point at is the key")
    void foreignKeyTarget() {
        KnownSchema.ForeignKey fk = new KnownSchema.ForeignKey("students", "department_id", "departments", "department_id");
        assertThat(snapshot(Dialect.MYSQL, Map.of(), List.of(fk), DEPARTMENTS, STUDENTS)
                .primaryKeyFor(DEPARTMENTS)).isEqualTo("department_id");
    }

    @Test
    @DisplayName("MongoDB uses _id, and anything else falls back to the first column")
    void fallbacks() {
        KnownSchema.Table orders = new KnownSchema.Table("orders", List.of("status", "_id"));
        assertThat(snapshot(Dialect.MONGODB, Map.of(), List.of(), orders).primaryKeyFor(orders)).isEqualTo("_id");
        assertThat(snapshot(Dialect.MYSQL, Map.of(), List.of(), STUDENTS).primaryKeyFor(STUDENTS)).isEqualTo("student_name");
    }

    @Test
    @DisplayName("uploaded DDL declares single-column keys; a composite key is left to the fallback")
    void parsed() {
        ParsedTable students = new ParsedTable("students", List.of(
                new ParsedColumn("student_name", "VARCHAR", List.of(), false, false, false, false),
                new ParsedColumn("student_id", "INT", List.of(), true, true, false, true)), List.of());
        ParsedTable enrolments = new ParsedTable("enrolments", List.of(
                new ParsedColumn("student_id", "INT", List.of(), true, false, false, true),
                new ParsedColumn("course_id", "INT", List.of(), true, false, false, true)), List.of());
        assertThat(new ParsedSchema(List.of(students, enrolments), List.of()).primaryKeys())
                .isEqualTo(Map.of("students", "student_id"));
    }

    @Test
    @DisplayName("the built-in demos declare their keys")
    void builtIns() {
        assertThat(KnownSchema.builtIn().primaryKeyFor(KnownSchema.CUSTOMERS)).isEqualTo("id");
        SchemaSnapshot mongo = KnownMongoSchema.builtIn();
        assertThat(mongo.primaryKeyFor(mongo.tables().get(0))).isEqualTo("_id");
    }
}
