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

/** Joins chosen from the question's wording, column synonyms and abbreviated column names. */
class JoinWordingTest {

    private static KnownSchema.ForeignKey fk(String from, String fromColumn, String to, String toColumn) {
        return new KnownSchema.ForeignKey(from, fromColumn, to, toColumn);
    }

    private static final List<KnownSchema.Table> CAMPUS_TABLES = List.of(
            new KnownSchema.Table("departments", List.of("dept_id", "code", "name", "parent_dept_id", "head_faculty_id")),
            new KnownSchema.Table("faculty", List.of("faculty_id", "full_name", "email", "dept_id")),
            new KnownSchema.Table("programs", List.of("program_id", "name", "dept_id")),
            new KnownSchema.Table("students", List.of("student_id", "full_name", "program_id", "advisor_id", "admission_year")),
            new KnownSchema.Table("offers", List.of("offer_id", "student_id", "ctc_lpa", "offered_on")));

    private static final List<KnownSchema.ForeignKey> CAMPUS_KEYS = List.of(
            fk("faculty", "dept_id", "departments", "dept_id"),
            fk("departments", "head_faculty_id", "faculty", "faculty_id"),
            fk("departments", "parent_dept_id", "departments", "dept_id"),
            fk("programs", "dept_id", "departments", "dept_id"),
            fk("students", "program_id", "programs", "program_id"),
            fk("students", "advisor_id", "faculty", "faculty_id"),
            fk("offers", "student_id", "students", "student_id"));

    private static ActiveSchemaProvider schema(List<KnownSchema.Table> tables, List<KnownSchema.ForeignKey> keys) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("u", "u.sql", "querysense_uploads", tables, keys, Map.of(), Instant.now()));
        return schemas;
    }

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("departments"), eq("name"))).thenReturn(List.of("Mathematics", "Computer Science"));
        return m;
    }

    private static RuleBuildResult ask(ActiveSchemaProvider schemas, String question) {
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB))
                .parse(question, Dialect.MYSQL);
    }

    private static RuleBuildResult campus(String question) {
        return ask(schema(CAMPUS_TABLES, CAMPUS_KEYS), question);
    }

    @Test
    @DisplayName("of two keys between the same tables, the plain one is used when the question names no role")
    void plainKeyWhenNoRoleNamed() {
        RuleBuildResult result = campus("show faculty in the mathematics department");

        assertThat(result.trace().declineReason()).isNull();
        assertThat(result.sql()).contains("JOIN departments ON faculty.dept_id = departments.dept_id")
                .contains("departments.name = 'mathematics'");
        assertThat(result.interpretations()).extracting(Interpretation::kind)
                .contains(SchemaLexicon.MatchKind.CHOSEN_JOIN);
        assertThat(result.interpreted()).isTrue();
    }

    @Test
    @DisplayName("a role the question names picks the key that carries it")
    void namedRolePicksItsKey() {
        RuleBuildResult result = campus("list departments with their head faculty");

        assertThat(result.trace().declineReason()).isNull();
        assertThat(result.sql()).contains("departments.head_faculty_id = faculty.faculty_id");
        assertThat(result.interpretations()).contains(
                new Interpretation("head", "departments.head_faculty_id", SchemaLexicon.MatchKind.CHOSEN_JOIN));
    }

    @Test
    @DisplayName("of equally short chains, the only one with no unnamed role is used")
    void chainWithoutUnnamedRoles() {
        RuleBuildResult result = campus("departments and students");

        assertThat(result.trace().declineReason()).isNull();
        assertThat(result.sql()).contains("JOIN programs ON departments.dept_id = programs.dept_id")
                .contains("JOIN students ON programs.program_id = students.program_id")
                .doesNotContain("advisor_id").doesNotContain("head_faculty_id");
    }

    @Test
    @DisplayName("two keys that both carry an unnamed role still decline")
    void unnamedRolesStillDecline() {
        ActiveSchemaProvider schemas = schema(List.of(
                        new KnownSchema.Table("orders", List.of("id", "billing_address_id", "shipping_address_id")),
                        new KnownSchema.Table("addresses", List.of("id", "city"))),
                List.of(fk("orders", "billing_address_id", "addresses", "id"),
                        fk("orders", "shipping_address_id", "addresses", "id")));

        assertThat(ask(schemas, "orders with addresses").trace().declineReason())
                .contains("billing_address_id").contains("shipping_address_id");
        assertThat(ask(schemas, "orders with their shipping addresses").sql())
                .contains("orders.shipping_address_id = addresses.id");
    }

    @Test
    @DisplayName("a role word reaches the table its key points at, and that key is the join")
    void roleWordReachesTable() {
        RuleBuildResult result = campus("list each department and their department heads");

        assertThat(result.trace().declineReason()).isNull();
        assertThat(result.sql()).contains("JOIN faculty ON departments.head_faculty_id = faculty.faculty_id")
                .doesNotContain("faculty.dept_id");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("heads", "departments.head_faculty_id", SchemaLexicon.MatchKind.CHOSEN_JOIN));
        assertThat(result.interpreted()).isTrue();
    }

    @Test
    @DisplayName("a role spelled by an abbreviation reaches its table too")
    void abbreviatedRoleReachesTable() {
        assertThat(ask(schema(COMPANY_TABLES, COMPANY_KEYS), "Show departments and their managers").sql())
                .contains("DEPARTMENT.Mgr_ssn = EMPLOYEE.Ssn");
    }

    @Test
    @DisplayName("a role word that another column of the table also carries reaches nothing")
    void roleWordSharedWithColumnStaysPut() {
        ActiveSchemaProvider schemas = schema(List.of(
                        new KnownSchema.Table("orders", List.of("id", "shipping_address_id", "shipping_date")),
                        new KnownSchema.Table("addresses", List.of("id", "city"))),
                List.of(fk("orders", "shipping_address_id", "addresses", "id")));

        assertThat(ask(schemas, "show the shipping date of orders").sql())
                .isEqualTo("SELECT shipping_date FROM orders;");
    }

    @Test
    @DisplayName("a decline over two chains through the same table names the keys, not the table twice")
    void declineNamesTheKeys() {
        ActiveSchemaProvider schemas = schema(List.of(
                        new KnownSchema.Table("departments", List.of("dept_id", "name", "head_faculty_id")),
                        new KnownSchema.Table("faculty", List.of("faculty_id", "full_name", "dept_id")),
                        new KnownSchema.Table("students", List.of("student_id", "full_name", "advisor_id"))),
                List.of(fk("faculty", "dept_id", "departments", "dept_id"),
                        fk("departments", "head_faculty_id", "faculty", "faculty_id"),
                        fk("students", "advisor_id", "faculty", "faculty_id")));

        String reason = ask(schemas, "departments and students").trace().declineReason();

        assertThat(reason).doesNotContain("through faculty, or through faculty")
                .contains("faculty.dept_id").contains("departments.head_faculty_id");
    }

    @Test
    @DisplayName("a built-in synonym reaches one word of a two-word column")
    void synonymReachesWordOfColumn() {
        RuleBuildResult result = campus("show the average salary of offers");

        assertThat(result.trace().declineReason()).isNull();
        assertThat(result.sql()).contains("AVG(ctc_lpa)");
        assertThat(result.interpretations()).contains(
                new Interpretation("salary", "offers.ctc_lpa", SchemaLexicon.MatchKind.SYNONYM));
    }

    private static final List<KnownSchema.Table> COMPANY_TABLES = List.of(
            new KnownSchema.Table("EMPLOYEE", List.of("Fname", "Minit", "Lname", "Ssn", "Bdate", "Sex", "Salary",
                    "Super_ssn", "Dno")),
            new KnownSchema.Table("DEPARTMENT", List.of("Dname", "Dnumber", "Mgr_ssn")));

    private static final List<KnownSchema.ForeignKey> COMPANY_KEYS = List.of(
            fk("EMPLOYEE", "Dno", "DEPARTMENT", "Dnumber"),
            fk("DEPARTMENT", "Mgr_ssn", "EMPLOYEE", "Ssn"),
            fk("EMPLOYEE", "Super_ssn", "EMPLOYEE", "Ssn"));

    @Test
    @DisplayName("an abbreviated column name is reached by the words it stands for")
    void abbreviatedColumns() {
        RuleBuildResult result = ask(schema(COMPANY_TABLES, COMPANY_KEYS), "Show the first name and last name of employees");

        assertThat(result.sql()).isEqualTo("SELECT Fname, Lname FROM EMPLOYEE;");
        assertThat(result.interpretations()).contains(
                new Interpretation("first name", "EMPLOYEE.Fname", SchemaLexicon.MatchKind.ABBREVIATION),
                new Interpretation("last name", "EMPLOYEE.Lname", SchemaLexicon.MatchKind.ABBREVIATION));
        assertThat(result.interpreted()).isTrue();
    }

    @Test
    @DisplayName("an abbreviated key that names its table is the plain key")
    void abbreviatedPlainKey() {
        RuleBuildResult result = ask(schema(COMPANY_TABLES, COMPANY_KEYS), "Show employees with their department");

        assertThat(result.sql()).contains("EMPLOYEE.Dno = DEPARTMENT.Dnumber");
    }

    @Test
    @DisplayName("an LLM join on two columns the schema doesn't relate is reported")
    void unrelatedJoinIsReported() {
        GeneratedQueryValidator validator = new GeneratedQueryValidator(schema(CAMPUS_TABLES, CAMPUS_KEYS));

        assertThat(validator.namesUnknownIdentifiers("SELECT d.name, AVG(s.admission_year) FROM departments d "
                + "JOIN students s ON d.dept_id = s.program_id GROUP BY d.dept_id, d.name;", "MySQL"))
                .get().asString().contains("departments.dept_id").contains("students.program_id");
        assertThat(validator.namesUnknownIdentifiers("SELECT f.full_name FROM faculty f "
                + "JOIN departments d ON d.head_faculty_id = f.faculty_id;", "MySQL")).isEmpty();
        assertThat(validator.namesUnknownIdentifiers("SELECT s.full_name FROM students s "
                + "JOIN offers o ON o.student_id = s.student_id;", "MySQL")).isEmpty();
    }
}
