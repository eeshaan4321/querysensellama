package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
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

/** A verb in the question names the date or year column formed from its noun. */
class DerivedVerbTest {

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("students"), eq("admission_year"))).thenReturn(List.of("2022", "2024"));
        return m;
    }

    private static RuleBuildResult ask(String question, List<KnownSchema.Table> tables, Map<String, Map<String, String>> types) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("u", "u.sql", "uploads", tables, List.of(), Map.of(), Instant.now(),
                Dialect.MYSQL, List.of(), types));
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB))
                .parse(question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("admittedInYear: admitted names admission_year")
    void admittedInYear() {
        RuleBuildResult result = ask("students admitted in 2024",
                List.of(new KnownSchema.Table("students", List.of("student_id", "full_name", "admission_year"))),
                Map.of("students", Map.of("student_id", "INT", "full_name", "VARCHAR", "admission_year", "SMALLINT")));
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql())
                .isEqualTo("SELECT * FROM students WHERE admission_year >= 2024 AND admission_year < 2025;");
        assertThat(result.interpretations()).contains(
                new Interpretation("admitted", "students.admission_year", SchemaLexicon.MatchKind.DERIVED));
    }

    @Test
    @DisplayName("enrolledLastMonth: enrolled names enrollment_date among two date columns")
    void enrolledLastMonth() {
        RuleBuildResult result = ask("show enrollments enrolled last month",
                List.of(new KnownSchema.Table("enrollments",
                        List.of("enrollment_id", "student_id", "enrollment_date", "completed_on"))),
                Map.of("enrollments", Map.of("enrollment_id", "INT", "student_id", "INT",
                        "enrollment_date", "DATE", "completed_on", "DATE")));
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).contains("enrollment_date >=").doesNotContain("completed_on");
    }

    @Test
    @DisplayName("bornAfter: born names Bdate")
    void bornAfter() {
        RuleBuildResult result = ask("employees born after 1990",
                List.of(new KnownSchema.Table("EMPLOYEE", List.of("Fname", "Ssn", "Bdate", "hire_date"))),
                Map.of("EMPLOYEE", Map.of("Fname", "VARCHAR", "Ssn", "CHAR", "Bdate", "DATE", "hire_date", "DATE")));
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).contains("Bdate").doesNotContain("hire_date");
    }

    @Test
    @DisplayName("commandVerbsNeverDerive: instruction words name no column")
    void commandVerbsNeverDerive() {
        List<KnownSchema.Table> tables = List.of(new KnownSchema.Table("rows",
                List.of("row_id", "listing_date", "showing_date", "order_date")));
        Map<String, Map<String, String>> types = Map.of("rows", Map.of("row_id", "INT",
                "listing_date", "DATE", "showing_date", "DATE", "order_date", "DATE"));
        for (String question : List.of("list rows last week", "show rows last week", "rows ordered by order date")) {
            RuleBuildResult result = ask(question, tables, types);
            assertThat(result.interpretations()).as(question)
                    .noneMatch(i -> i.kind() == SchemaLexicon.MatchKind.DERIVED);
        }
    }
}
