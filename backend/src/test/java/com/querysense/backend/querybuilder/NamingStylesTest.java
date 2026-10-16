package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NamingStylesTest {

    private static final Map<String, List<String>> PREFIXED = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> TYPES = new LinkedHashMap<>();

    static {
        PREFIXED.put("departments", List.of("department_id", "department_name"));
        PREFIXED.put("professors", List.of("professor_id", "professor_name", "email", "department_id"));
        PREFIXED.put("students", List.of("student_id", "student_name", "email", "admission_year", "joined_date", "department_id"));
        PREFIXED.put("courses", List.of("course_id", "course_name", "course_credits", "professor_id"));
        TYPES.put("department_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("department_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("professor_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("professor_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("email", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("student_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("student_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("admission_year", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("joined_date", Map.of("MYSQL", "DATE", "MONGODB", "date"));
        TYPES.put("course_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("course_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("course_credits", Map.of("MYSQL", "INT", "MONGODB", "int"));
    }

    enum Style {
        PREFIXED_SNAKE(t -> t, c -> c, "student name", "admission year", "admission years", "credits"),
        CAMEL(t -> t, NamingStylesTest::camel, "student name", "admission year", "admission years", "credits"),
        PASCAL(NamingStylesTest::pascal, c -> pascal(c).replaceAll("Id$", "ID"),
                "student name", "admission year", "admission years", "credits"),
        UPPER(String::toUpperCase, String::toUpperCase, "student name", "admission year", "admission years", "credits"),
        PLAIN(t -> t, c -> Map.of("department_name", "name", "professor_name", "name", "student_name", "name",
                "course_name", "name", "admission_year", "year", "joined_date", "joined",
                "course_credits", "credits").getOrDefault(c, c), "name", "year", "years", "credits"),
        ABBREVIATED(t -> t, c -> Map.ofEntries(Map.entry("department_id", "dept_id"), Map.entry("department_name", "dept_nm"),
                Map.entry("professor_id", "prof_id"), Map.entry("professor_name", "prof_nm"),
                Map.entry("student_id", "stu_id"), Map.entry("student_name", "stu_nm"),
                Map.entry("admission_year", "adm_yr"), Map.entry("joined_date", "jnd_dt"),
                Map.entry("course_id", "crs_id"), Map.entry("course_name", "crs_nm"),
                Map.entry("course_credits", "crs_cr")).getOrDefault(c, c), "stu nm", "adm yr", "adm yrs", "crs cr");

        final UnaryOperator<String> table;
        final UnaryOperator<String> column;
        final String studentName;
        final String year;
        final String years;
        final String credits;

        Style(UnaryOperator<String> table, UnaryOperator<String> column, String studentName, String year,
              String years, String credits) {
            this.table = table;
            this.column = column;
            this.studentName = studentName;
            this.year = year;
            this.years = years;
            this.credits = credits;
        }
    }

    private static String camel(String snake) {
        String pascal = pascal(snake);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String pascal(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static List<String> questions(Style s) {
        return List.of(
                "show only the " + s.studentName + " and email of students",
                "students with " + s.year + " 2022",
                "courses with " + s.credits + " above 3",
                "courses with " + s.credits + " between 2 and 4",
                "students with " + s.studentName + " not asha",
                "students sorted by " + s.year + " descending",
                "top 3 students by " + s.year,
                "count students per " + s.year,
                "distinct " + s.years + " of students",
                "average and highest " + s.credits + " of courses",
                "students from last month",
                "students of departments in physics",
                "which departments have more than three professors");
    }

    private static final int JOINED_FILTER = 11;
    private static final int GROUPED_COUNT = 12;

    private static QueryIntentParser parser(Style s, Dialect dialect) {
        List<KnownSchema.Table> tables = new ArrayList<>();
        Map<String, Map<String, String>> fieldTypes = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        PREFIXED.forEach((table, columns) -> {
            List<String> named = new ArrayList<>();
            Map<String, String> types = new LinkedHashMap<>();
            if (dialect == Dialect.MONGODB) {
                named.add("_id");
                types.put("_id", "objectId");
            }
            for (String c : columns) {
                named.add(s.column.apply(c));
                types.put(s.column.apply(c), TYPES.get(c).get(dialect.name()));
            }
            tables.add(new KnownSchema.Table(s.table.apply(table), named));
            fieldTypes.put(s.table.apply(table), types);
            keys.put(s.table.apply(table), s.column.apply(columns.get(0)));
        });
        List<KnownSchema.ForeignKey> fks = List.of(
                new KnownSchema.ForeignKey(s.table.apply("professors"), s.column.apply("department_id"),
                        s.table.apply("departments"), s.column.apply("department_id")),
                new KnownSchema.ForeignKey(s.table.apply("students"), s.column.apply("department_id"),
                        s.table.apply("departments"), s.column.apply("department_id")),
                new KnownSchema.ForeignKey(s.table.apply("courses"), s.column.apply("professor_id"),
                        s.table.apply("professors"), s.column.apply("professor_id")));
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-" + s, s.name(), "querysense_uploads", tables, fks, Map.of(),
                Instant.now(), dialect, List.of(), fieldTypes, Map.of(), Map.of(), keys));
        SchemaMetadata data = mock(SchemaMetadata.class);
        when(data.dialect()).thenReturn(dialect);
        when(data.getColumnNames(anyString())).thenReturn(List.of());
        when(data.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(data.getDistinctValues(eq(s.table.apply("students")), eq(s.column.apply("student_name"))))
                .thenReturn(List.of("Asha", "Ravi"));
        when(data.getDistinctValues(eq(s.table.apply("departments")), eq(s.column.apply("department_name"))))
                .thenReturn(List.of("Physics", "Chemistry"));
        SchemaMetadata other = mock(SchemaMetadata.class);
        when(other.dialect()).thenReturn(dialect == Dialect.MYSQL ? Dialect.MONGODB : Dialect.MYSQL);
        return dialect == Dialect.MYSQL ? TestParsers.parser(schemas, data, other) : TestParsers.parser(schemas, other, data);
    }

    private static String translate(String query, Style s) {
        Map<String, String> names = new LinkedHashMap<>();
        PREFIXED.forEach((table, columns) -> {
            names.put(table, s.table.apply(table));
            columns.forEach(c -> names.put(c, s.column.apply(c)));
        });
        List<String> order = new ArrayList<>(names.keySet());
        order.sort(Comparator.comparingInt(String::length).reversed());
        String alternation = String.join("|", order);
        Pattern alias = Pattern.compile("(?<![A-Za-z0-9_])(count|sum|avg|min|max)_((?:" + alternation + ")(?:_(?:"
                + alternation + "))*)(?![A-Za-z0-9_])");
        Pattern name = Pattern.compile("(?<![A-Za-z0-9_])(" + alternation + ")(?![A-Za-z0-9_])");
        Pattern part = Pattern.compile(alternation);
        StringBuilder out = new StringBuilder();
        Matcher token = Pattern.compile(alias.pattern() + "|" + name.pattern()).matcher(query);
        while (token.find()) {
            String replacement;
            if (token.group(1) != null) {
                StringBuilder body = new StringBuilder(token.group(1)).append('_');
                Matcher parts = part.matcher(token.group(2));
                int at = 0;
                while (at < token.group(2).length() && parts.find(at) && parts.start() == at) {
                    body.append(names.get(parts.group()));
                    at = parts.end();
                    if (at < token.group(2).length()) {
                        body.append('_');
                        at++;
                    }
                }
                replacement = body.toString();
            } else {
                replacement = names.get(token.group(3));
            }
            token.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        token.appendTail(out);
        return out.toString();
    }

    private static String outcome(RuleBuildResult result) {
        return result.built() ? result.sql() : "DECLINED";
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    @DisplayName("the reference style builds every question it should")
    void referenceBuilds(Dialect dialect) {
        QueryIntentParser parser = parser(Style.PREFIXED_SNAKE, dialect);
        List<String> qs = questions(Style.PREFIXED_SNAKE);
        for (int i = 0; i < qs.size(); i++) {
            if (dialect == Dialect.MONGODB && (i == JOINED_FILTER || i == GROUPED_COUNT)) {
                continue;
            }
            RuleBuildResult result = parser.parse(qs.get(i), dialect);
            assertThat(result.built()).as(dialect + ": " + qs.get(i) + " → " + (result.trace() == null ? "no trace" : result.trace().declineReason())).isTrue();
        }
        assertThat(outcome(parser.parse(qs.get(0), dialect)))
                .isEqualTo(dialect == Dialect.MYSQL ? "SELECT student_name, email FROM students;"
                        : "db.students.find({}, {\"student_name\": 1, \"email\": 1, \"_id\": 0})");
    }

    @ParameterizedTest
    @EnumSource(Style.class)
    @DisplayName("every style builds the same query as the reference, with its own names, in both dialects")
    void everyStyleMatches(Style style) {
        for (Dialect dialect : Dialect.values()) {
            QueryIntentParser reference = parser(Style.PREFIXED_SNAKE, dialect);
            QueryIntentParser styled = parser(style, dialect);
            List<String> referenceQs = questions(Style.PREFIXED_SNAKE);
            List<String> styledQs = questions(style);
            for (int i = 0; i < referenceQs.size(); i++) {
                String expected = outcome(reference.parse(referenceQs.get(i), dialect));
                String actual = outcome(styled.parse(styledQs.get(i), dialect));
                assertThat(actual).as(style + " " + dialect + ": " + styledQs.get(i))
                        .isEqualTo("DECLINED".equals(expected) ? expected : translate(expected, style));
            }
        }
    }

    @Test
    @DisplayName("on the abbreviated schema, words the schema doesn't use decline")
    void abbreviationBoundary() {
        QueryIntentParser parser = parser(Style.ABBREVIATED, Dialect.MYSQL);
        assertThat(parser.parse("show only the stu nm and email of students", Dialect.MYSQL).built()).isTrue();
        assertThat(parser.parse("show only the student name and email of students", Dialect.MYSQL).built()).isFalse();
    }
}
