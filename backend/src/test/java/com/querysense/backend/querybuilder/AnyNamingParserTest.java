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

    @Test
    @DisplayName("filters and comparisons read multi-word columns")
    void conditions() {
        assertThat(sql("students with admission year 2022"))
                .isEqualTo("SELECT * FROM students WHERE admission_year = 2022;");
        assertThat(sql("students where admission_year is 2022"))
                .isEqualTo("SELECT * FROM students WHERE admission_year = 2022;");
        assertThat(sql("students with admission year above 2020"))
                .isEqualTo("SELECT * FROM students WHERE admission_year > 2020;");
        assertThat(sql("students with student name not asha"))
                .isEqualTo("SELECT * FROM students WHERE student_name != 'asha';");
    }

    @Test
    @DisplayName("a value right after the column is the value, not part of the column")
    void valueAfterColumn() {
        assertThat(sql("enrollments with grade a")).isEqualTo("SELECT * FROM enrollments WHERE grade = 'a';");
    }

    private QueryIntentParser builtInParser() {
        SchemaMetadata mysql = mock(SchemaMetadata.class);
        when(mysql.dialect()).thenReturn(Dialect.MYSQL);
        when(mysql.getColumnNames(anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        SchemaMetadata mongo = mock(SchemaMetadata.class);
        when(mongo.dialect()).thenReturn(Dialect.MONGODB);
        return TestParsers.parser(new ActiveSchemaProvider(), mysql, mongo);
    }

    @Test
    @DisplayName("a number right after with is not read as a column")
    void numberAfterWith() {
        RuleBuildResult result = builtInParser().parse("customers with 3 orders", Dialect.MYSQL);
        assertThat(result.built()).as(String.valueOf(result.trace().declineReason())).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM customers JOIN orders ON customers.id = orders.customer_id;");
    }

    @Test
    @DisplayName("a number right before an operator is not read as the compared column")
    void numberBeforeOperator() {
        QueryIntentParser builtIn = builtInParser();
        RuleBuildResult plain = builtIn.parse("orders 5 more than 3", Dialect.MYSQL);
        assertThat(plain.built()).as(String.valueOf(plain.trace().declineReason())).isTrue();
        assertThat(plain.sql()).isEqualTo("SELECT * FROM orders;");
        RuleBuildResult withIs = builtIn.parse("orders 5 is more than 3", Dialect.MYSQL);
        assertThat(withIs.built()).isFalse();
        assertThat(withIs.trace().declineReason())
                .isEqualTo("The question compares \"is\", which is not a column of orders.");
    }

    @Test
    @DisplayName("sorting, ranking, grouping, distinct and totals read multi-word columns")
    void shapes() {
        assertThat(sql("students sorted by admission year descending"))
                .isEqualTo("SELECT * FROM students ORDER BY admission_year DESC;");
        assertThat(sql("top 3 students by admission year"))
                .isEqualTo("SELECT * FROM students ORDER BY admission_year DESC LIMIT 3;");
        assertThat(sql("count students per admission year")).contains("GROUP BY admission_year");
        assertThat(sql("distinct admission years of students"))
                .isEqualTo("SELECT DISTINCT admission_year FROM students;");
        assertThat(sql("average and highest admission year of students"))
                .contains("AVG(admission_year)").contains("MAX(admission_year)");
    }

    @Test
    @DisplayName("a grouped count shows the department's key and its name")
    void groupedCount() {
        assertThat(sql("which departments have more than three professors"))
                .startsWith("SELECT departments.department_name, departments.department_id, COUNT(professors.professor_id)");
    }

    @Test
    @DisplayName("sorting, distinct and grouping keep the readings they had before column phrases")
    void shapeParity() {
        QueryIntentParser builtIn = builtInParser();
        assertThat(builtIn.parse("customers sorted by name orders desc", Dialect.MYSQL).sql())
                .isEqualTo("SELECT * FROM customers JOIN orders ON customers.id = orders.customer_id ORDER BY customers.name DESC;");
        assertThat(builtIn.parse("distinct city and name of customers", Dialect.MYSQL).sql())
                .isEqualTo("SELECT city, name FROM customers;");
        assertThat(builtIn.parse("customers sorted by 2", Dialect.MYSQL).sql()).isEqualTo("SELECT * FROM customers;");
        assertThat(builtIn.parse("count orders per 2", Dialect.MYSQL).sql()).isEqualTo("SELECT COUNT(*) FROM orders;");
        RuleBuildResult descend = builtIn.parse("products sorted by price descend", Dialect.MYSQL);
        assertThat(descend.built()).isFalse();
        assertThat(descend.trace().declineReason()).isEqualTo("The question uses \"descend\", which isn't a table, column "
                + "or stored value of the active schema, so this engine can't be sure it reads the whole question.");
    }

    @Test
    @DisplayName("a total over a multi-word column names the whole column phrase in the trace")
    void multiWordTotalTrace() {
        RuleBuildResult result = builtInParser().parse("average and highest unit price of order items", Dialect.MYSQL);
        assertThat(result.built()).as(String.valueOf(result.trace().declineReason())).isTrue();
        assertThat(result.trace().bindings()).anySatisfy(b -> assertThat(b.phrase()).contains("unit price"));
    }
}
