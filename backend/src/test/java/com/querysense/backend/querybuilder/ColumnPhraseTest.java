package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnPhraseTest {

    private static final KnownSchema.Table STUDENTS = new KnownSchema.Table("students",
            List.of("student_id", "student_name", "admission_year", "grade", "department_id"));
    private static final KnownSchema.Table DEPARTMENTS = new KnownSchema.Table("departments",
            List.of("department_id", "department_name"));
    private static final KnownSchema.Table SECTIONS = new KnownSchema.Table("sections",
            List.of("section_id", "academic_year", "semester_year"));

    private ColumnPhrase phrases;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-c", "c", "querysense_uploads",
                List.of(STUDENTS, DEPARTMENTS, SECTIONS), List.of(), Map.of(), Instant.now()));
        phrases = new ColumnPhrase(new SchemaBinder(schemas));
    }

    @Test
    @DisplayName("after a keyword, the longest run naming one column wins")
    void after() {
        String text = "students where admission year is 2022";
        ColumnPhrase.Reading reading = phrases.after(text, text.indexOf("admission"), List.of(STUDENTS)).orElseThrow();
        assertThat(reading.column()).isEqualTo("admission_year");
        assertThat(reading.phrase()).isEqualTo("admission year");
        assertThat(text.substring(reading.start(), reading.end())).isEqualTo("admission year");
    }

    @Test
    @DisplayName("a value word after the column is left alone")
    void valueAfter() {
        String text = "students with grade a";
        ColumnPhrase.Reading reading = phrases.after(text, text.indexOf("grade"), List.of(STUDENTS)).orElseThrow();
        assertThat(reading.column()).isEqualTo("grade");
        assertThat(reading.end()).isEqualTo(text.indexOf(" a"));
    }

    @Test
    @DisplayName("before a keyword, the longest run ending there wins")
    void before() {
        String text = "students with admission year above 2020";
        ColumnPhrase.Reading reading = phrases.before(text, text.indexOf("above"), List.of(STUDENTS)).orElseThrow();
        assertThat(reading.column()).isEqualTo("admission_year");
    }

    @Test
    @DisplayName("a phrase only a joined table has binds there")
    void chain() {
        String text = "students with department name physics";
        ColumnPhrase.Reading reading = phrases.after(text, text.indexOf("department"),
                List.of(STUDENTS, DEPARTMENTS)).orElseThrow();
        assertThat(reading.table()).isEqualTo(DEPARTMENTS);
        assertThat(reading.column()).isEqualTo("department_name");
    }

    @Test
    @DisplayName("an ambiguous phrase declines, and nothing found is empty")
    void ambiguousAndEmpty() {
        String text = "sections with year 2024";
        assertThatThrownBy(() -> phrases.after(text, text.indexOf("year"), List.of(SECTIONS)))
                .isInstanceOf(AmbiguousWordException.class)
                .hasMessageContaining("sections.academic_year")
                .hasMessageContaining("sections.semester_year");
        assertThat(phrases.after("students with cats", 14, List.of(STUDENTS))).isEmpty();
    }

    @Test
    @DisplayName("the exact-only form reads the column ending before the keyword")
    void exactOnly() {
        String text = "students with student name not asha";
        assertThat(phrases.beforeExact(text, text.indexOf(" not") + 1, STUDENTS).orElseThrow().column())
                .isEqualTo("student_name");
    }

    @Test
    @DisplayName("a phrase two joined tables both have declines")
    void chainAmbiguous() {
        String text = "sections with department id 4";
        assertThatThrownBy(() -> phrases.after(text, text.indexOf("department"),
                List.of(SECTIONS, STUDENTS, DEPARTMENTS)))
                .isInstanceOf(AmbiguousWordException.class)
                .hasMessageContaining("students.department_id")
                .hasMessageContaining("departments.department_id");
    }
}
