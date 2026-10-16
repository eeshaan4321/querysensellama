package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnyNamingLexiconTest {

    private final ActiveSchemaProvider schemas = new ActiveSchemaProvider();
    private final SchemaLexicon lexicon = new SchemaLexicon(schemas, Synonyms.builtIn());

    private void publish(Dialect dialect, KnownSchema.Table... tables) {
        schemas.publish(new SchemaSnapshot("upload-n", "n", "querysense_uploads", List.of(tables), List.of(),
                Map.of(), Instant.now(), dialect, List.of(), Map.of(), Map.of(), Map.of()));
    }

    private String bound(KnownSchema.Table table, String phrase) {
        SchemaLexicon.ColumnMatch match = lexicon.column(table, phrase, SchemaAliases.TableAliases.NONE);
        assertThat(match).as(phrase).isInstanceOf(SchemaLexicon.Bound.class);
        return ((SchemaLexicon.Bound) match).binding().item();
    }

    @Test
    @DisplayName("a prefixed column binds by its full words and without its table's word")
    void prefixed() {
        KnownSchema.Table students = new KnownSchema.Table("students",
                List.of("student_id", "student_name", "admission_year"));
        publish(Dialect.MYSQL, students);
        assertThat(bound(students, "student name")).isEqualTo("student_name");
        assertThat(bound(students, "name")).isEqualTo("student_name");
        assertThat(bound(students, "admission year")).isEqualTo("admission_year");
        assertThat(bound(students, "admission years")).isEqualTo("admission_year");
        assertThat(bound(students, "year")).isEqualTo("admission_year");
    }

    @Test
    @DisplayName("camelCase, PascalCase and UPPER_SNAKE bind the same way")
    void otherStyles() {
        KnownSchema.Table camel = new KnownSchema.Table("students", List.of("studentId", "studentName", "admissionYear"));
        publish(Dialect.MYSQL, camel);
        assertThat(bound(camel, "student name")).isEqualTo("studentName");
        assertThat(bound(camel, "admissionyear")).isEqualTo("admissionYear");

        KnownSchema.Table pascal = new KnownSchema.Table("Students", List.of("StudentID", "StudentName"));
        publish(Dialect.MYSQL, pascal);
        assertThat(bound(pascal, "student id")).isEqualTo("StudentID");
        assertThat(bound(pascal, "name")).isEqualTo("StudentName");

        KnownSchema.Table upper = new KnownSchema.Table("STUDENTS", List.of("STUDENT_ID", "STUDENT_NAME"));
        publish(Dialect.MYSQL, upper);
        assertThat(bound(upper, "student name")).isEqualTo("STUDENT_NAME");
    }

    @Test
    @DisplayName("digits split off and still bind when typed together")
    void digits() {
        KnownSchema.Table people = new KnownSchema.Table("people", List.of("id", "address2"));
        publish(Dialect.MYSQL, people);
        assertThat(bound(people, "address 2")).isEqualTo("address2");
        assertThat(bound(people, "address2")).isEqualTo("address2");
    }

    @Test
    @DisplayName("abbreviations bind only as written")
    void abbreviations() {
        KnownSchema.Table students = new KnownSchema.Table("students", List.of("stu_id", "stu_nm"));
        publish(Dialect.MYSQL, students);
        assertThat(bound(students, "stu nm")).isEqualTo("stu_nm");
        assertThat(lexicon.column(students, "student name", SchemaAliases.TableAliases.NONE))
                .isInstanceOf(SchemaLexicon.Unbound.class);
    }

    @Test
    @DisplayName("a word two columns end with is ambiguous, and their full words still bind")
    void ambiguous() {
        KnownSchema.Table sections = new KnownSchema.Table("sections",
                List.of("section_id", "academic_year", "semester_year"));
        publish(Dialect.MYSQL, sections);
        SchemaLexicon.ColumnMatch year = lexicon.column(sections, "year", SchemaAliases.TableAliases.NONE);
        assertThat(year).isInstanceOf(SchemaLexicon.Ambiguous.class);
        assertThat(((SchemaLexicon.Ambiguous) year).ambiguity().candidates())
                .containsExactlyInAnyOrder("academic_year", "semester_year");
        assertThat(bound(sections, "academic year")).isEqualTo("academic_year");
    }

    @Test
    @DisplayName("table names in any style are found in a question")
    void tables() {
        publish(Dialect.MYSQL, new KnownSchema.Table("StudentRecords", List.of("id")));
        SchemaLexicon.TableMatches matches = lexicon.tables("show student records", Dialect.MYSQL);
        assertThat(matches.hits()).hasSize(1);
        assertThat(matches.hits().get(0).table().name()).isEqualTo("StudentRecords");
        assertThat(lexicon.tables("each student record", Dialect.MYSQL).hits()).hasSize(1);
    }

    @Test
    @DisplayName("an embedded MongoDB field binds without its parent's words")
    void embedded() {
        KnownSchema.Table orders = new KnownSchema.Table("orders", List.of("_id", "items.productId"));
        publish(Dialect.MONGODB, orders);
        assertThat(bound(orders, "product id")).isEqualTo("items.productId");
    }
}
