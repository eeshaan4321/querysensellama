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

/** Whole-year ranges on integer year columns. */
class YearColumnTest {

    private static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("students", List.of("student_id", "full_name", "admission_year")));

    private static final List<String> YEARS = List.of("2019", "2020", "2024");

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("students"), eq("admission_year"))).thenReturn(YEARS);
        when(m.getDistinctValues(eq("orders"), eq("year"))).thenReturn(YEARS);
        return m;
    }

    private static QueryIntentParser parser(Dialect dialect) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("u", "u.sql", "uploads", TABLES, List.of(), Map.of(), Instant.now(),
                dialect, List.of(), Map.of("students", Map.of("student_id", "INT", "full_name", "VARCHAR",
                        "admission_year", dialect == Dialect.MONGODB ? "int" : "SMALLINT"))));
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    private static String sql(String question) {
        RuleBuildResult result = parser(Dialect.MYSQL).parse(question, Dialect.MYSQL);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        return result.sql();
    }

    @Test
    @DisplayName("wholeYear: a year in the question becomes a range of year numbers")
    void wholeYear() {
        assertThat(sql("students with admission year in 2024"))
                .isEqualTo("SELECT * FROM students WHERE admission_year >= 2024 AND admission_year < 2025;");
    }

    @Test
    @DisplayName("since: an open-ended range keeps one bound")
    void since() {
        assertThat(sql("students with admission year since 2020"))
                .isEqualTo("SELECT * FROM students WHERE admission_year >= 2020;");
    }

    @Test
    @DisplayName("monthOnYearColumnDeclines: a month cannot be placed on a column that only holds years")
    void monthOnYearColumnDeclines() {
        RuleBuildResult result = parser(Dialect.MYSQL).parse("students with admission year last month", Dialect.MYSQL);
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("finer than a year");
    }

    @Test
    @DisplayName("mongo: year bounds are numbers, not ISODate")
    void mongo() {
        RuleBuildResult result = parser(Dialect.MONGODB).parse("students with admission year in 2024", Dialect.MONGODB);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("db.students.find({\"$and\": [{\"admission_year\": {\"$gte\": 2024}}, "
                + "{\"admission_year\": {\"$lt\": 2025}}]})");
    }

    @Test
    @DisplayName("dateColumnUnchanged: a DATE column still builds as before")
    void dateColumnUnchanged() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        QueryIntentParser demo = TestParsers.parser(schemas, DatesTest.metadata(Dialect.MYSQL), DatesTest.metadata(Dialect.MONGODB));
        RuleBuildResult result = demo.parse("orders from last month", Dialect.MYSQL);
        assertThat(result.sql())
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date < '2026-10-01';");
    }

    private static RuleBuildResult ordersAsk(String question) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("o", "o.sql", "uploads",
                List.of(new KnownSchema.Table("orders", List.of("order_id", "order_date", "year"))),
                List.of(), Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("orders", Map.of("order_id", "INT", "order_date", "DATE", "year", "INT"))));
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB))
                .parse(question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("yearColumnNotNamedByDatePhrase: the words of the range never name a column")
    void yearColumnNotNamedByDatePhrase() {
        RuleBuildResult result = ordersAsk("orders last year");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("could be checked against").contains("name one");
    }

    @Test
    @DisplayName("wholeYearWithDateAndYearColumnsDeclines: two fitting columns and none named")
    void wholeYearWithDateAndYearColumnsDeclines() {
        RuleBuildResult result = ordersAsk("orders in 2024");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("could be checked against").contains("name one");
    }

    @Test
    @DisplayName("beforeYear: an upper bound alone keeps one condition")
    void beforeYear() {
        assertThat(sql("students with admission year before 2024"))
                .isEqualTo("SELECT * FROM students WHERE admission_year < 2024;");
    }

    private static RuleBuildResult studyYearAsk(List<String> stored) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("s", "s.sql", "uploads",
                List.of(new KnownSchema.Table("students", List.of("student_id", "year"))),
                List.of(), Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("students", Map.of("student_id", "INT", "year", "SMALLINT"))));
        SchemaMetadata mysql = metadata(Dialect.MYSQL);
        when(mysql.getDistinctValues(eq("students"), eq("year"))).thenReturn(stored);
        return TestParsers.parser(schemas, mysql, metadata(Dialect.MONGODB)).parse("students in 2024", Dialect.MYSQL);
    }

    @Test
    @DisplayName("studyYearDeclines: a year column holding 1 to 4 is not read as calendar years")
    void studyYearDeclines() {
        RuleBuildResult result = studyYearAsk(List.of("1", "2", "3", "4"));
        assertThat(result.built()).as(result.sql()).isFalse();
        assertThat(result.trace().declineReason()).contains("date column");
    }

    @Test
    @DisplayName("unreadableYearDeclines: a year column whose values can't be read is not trusted")
    void unreadableYearDeclines() {
        RuleBuildResult result = studyYearAsk(List.of());
        assertThat(result.built()).as(result.sql()).isFalse();
        assertThat(result.trace().declineReason()).contains("date column");
    }

    @Test
    @DisplayName("calendarYearBuilds: the same column holding calendar years builds the range")
    void calendarYearBuilds() {
        RuleBuildResult result = studyYearAsk(List.of("2023", "2024"));
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM students WHERE year >= 2024 AND year < 2025;");
    }
}
