package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnyNamingParserTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-u", "university.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("departments", List.of("department_id", "department_name", "building")),
                        new KnownSchema.Table("professors", List.of("professor_id", "professor_name", "email", "department_id")),
                        new KnownSchema.Table("students", List.of("student_id", "student_name", "email", "department_id", "admission_year")),
                        new KnownSchema.Table("enrollments", List.of("enrollment_id", "student_id", "enrollment_date", "grade"))),
                List.of(new KnownSchema.ForeignKey("professors", "department_id", "departments", "department_id"),
                        new KnownSchema.ForeignKey("students", "department_id", "departments", "department_id"),
                        new KnownSchema.ForeignKey("enrollments", "student_id", "students", "student_id")),
                Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("departments", Map.of("department_id", "INT", "department_name", "VARCHAR", "building", "VARCHAR"),
                        "professors", Map.of("professor_id", "INT", "professor_name", "VARCHAR", "email", "VARCHAR", "department_id", "INT"),
                        "students", Map.of("student_id", "INT", "student_name", "VARCHAR", "email", "VARCHAR",
                                "department_id", "INT", "admission_year", "INT"),
                        "enrollments", Map.of("enrollment_id", "INT", "student_id", "INT", "enrollment_date", "DATE", "grade", "VARCHAR")),
                Map.of(), Map.of(),
                Map.of("departments", "department_id", "professors", "professor_id", "students", "student_id",
                        "enrollments", "enrollment_id")));
        SchemaMetadata mysql = mock(SchemaMetadata.class);
        when(mysql.dialect()).thenReturn(Dialect.MYSQL);
        when(mysql.getColumnNames(anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(eq("enrollments"), eq("grade"))).thenReturn(List.of("A", "B", "C"));
        when(mysql.getDistinctValues(eq("students"), eq("student_name"))).thenReturn(List.of("Asha", "Ravi"));
        SchemaMetadata mongo = mock(SchemaMetadata.class);
        when(mongo.dialect()).thenReturn(Dialect.MONGODB);
        parser = TestParsers.parser(schemas, mysql, mongo);
    }

    private String sql(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).as(question + " → " + result.trace().declineReason()).isTrue();
        return result.sql();
    }

    @Test
    @DisplayName("a column list naming the table's own word keeps its columns")
    void columnListKeepsColumns() {
        assertThat(sql("show only the student name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
        assertThat(sql("show only the student_name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
        assertThat(sql("show the student name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
        assertThat(sql("show only the name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
    }

    @Test
    @DisplayName("a phrase that names a table and no column falls through to no projection")
    void tableNamingPhraseIsNotAProjection() {
        RuleBuildResult result = parser.parse("show students of departments", Dialect.MYSQL);
        assertThat(result.built()).as(String.valueOf(result.trace().declineReason())).isTrue();
        assertThat(result.sql()).doesNotContain("student_name");
    }

    private QueryIntentParser parserOver(List<KnownSchema.ForeignKey> keys, KnownSchema.Table... tables) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        Map<String, Map<String, String>> types = new java.util.HashMap<>();
        for (KnownSchema.Table t : tables) {
            Map<String, String> cols = new java.util.HashMap<>();
            t.columns().forEach(c -> cols.put(c, "VARCHAR"));
            types.put(t.name(), cols);
        }
        schemas.publish(new SchemaSnapshot("upload-s", "small.sql", "querysense_uploads",
                List.of(tables), keys, Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                types, Map.of(), Map.of(), Map.of()));
        SchemaMetadata mysql = mock(SchemaMetadata.class);
        when(mysql.dialect()).thenReturn(Dialect.MYSQL);
        when(mysql.getColumnNames(anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        SchemaMetadata mongo = mock(SchemaMetadata.class);
        when(mongo.dialect()).thenReturn(Dialect.MONGODB);
        return TestParsers.parser(schemas, mysql, mongo);
    }

    @Test
    @DisplayName("a column name with a digit is read in a column list")
    void digitsInColumnList() {
        QueryIntentParser small = parserOver(List.of(), new KnownSchema.Table("people", List.of("person_id", "address2", "city")));
        RuleBuildResult result = small.parse("show only the address2 and city of people", Dialect.MYSQL);
        assertThat(result.built()).as(String.valueOf(result.trace().declineReason())).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT address2, city FROM people;");
    }

    @Test
    @DisplayName("a table word that is also a column of the subject reads as the column")
    void tableWordThatIsAColumn() {
        QueryIntentParser small = parserOver(List.of(),
                new KnownSchema.Table("departments", List.of("department_id", "department_name", "students")));
        RuleBuildResult result = small.parse("show only the students of departments", Dialect.MYSQL);
        assertThat(result.built()).as(String.valueOf(result.trace().declineReason())).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT students FROM departments;");
    }
}
