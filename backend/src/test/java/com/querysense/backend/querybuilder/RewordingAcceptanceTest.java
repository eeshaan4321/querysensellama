package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.params.provider.Arguments.of;

/** Reworded questions across four schemas build the expected query, and the loose readings decline. */
class RewordingAcceptanceTest {

    private static final String DECLINE = "DECLINE";

    private static KnownSchema.Table table(String name, String... columns) {
        return new KnownSchema.Table(name, List.of(columns));
    }

    private static KnownSchema.ForeignKey fk(String from, String fromColumn, String to, String toColumn) {
        return new KnownSchema.ForeignKey(from, fromColumn, to, toColumn);
    }

    private record Fixture(List<KnownSchema.Table> tables, List<KnownSchema.ForeignKey> keys,
                           Map<String, Map<String, String>> types, Map<String, List<String>> values) {}

    private static final Fixture CAMPUS = new Fixture(
            List.of(table("departments", "dept_id", "code", "name", "parent_dept_id", "head_faculty_id"),
                    table("faculty", "faculty_id", "full_name", "email", "dept_id"),
                    table("programs", "program_id", "name", "dept_id"),
                    table("students", "student_id", "full_name", "program_id", "advisor_id", "admission_year", "level"),
                    table("offers", "offer_id", "student_id", "ctc_lpa", "offered_on")),
            List.of(fk("faculty", "dept_id", "departments", "dept_id"),
                    fk("departments", "head_faculty_id", "faculty", "faculty_id"),
                    fk("departments", "parent_dept_id", "departments", "dept_id"),
                    fk("programs", "dept_id", "departments", "dept_id"),
                    fk("students", "program_id", "programs", "program_id"),
                    fk("students", "advisor_id", "faculty", "faculty_id"),
                    fk("offers", "student_id", "students", "student_id")),
            Map.of("students", Map.of("student_id", "INT", "full_name", "VARCHAR", "admission_year", "SMALLINT",
                    "level", "VARCHAR"),
                    "offers", Map.of("offer_id", "INT", "ctc_lpa", "DECIMAL", "offered_on", "DATE")),
            Map.of("students.level", List.of("Undergraduate", "Graduate", "Doctoral"),
                    "students.admission_year", List.of("2019", "2020", "2024")));

    private static final Fixture COMPANY = new Fixture(
            List.of(table("EMPLOYEE", "Fname", "Minit", "Lname", "Ssn", "Bdate", "Sex", "Salary", "Super_ssn", "Dno"),
                    table("DEPARTMENT", "Dname", "Dnumber", "Mgr_ssn")),
            List.of(fk("EMPLOYEE", "Dno", "DEPARTMENT", "Dnumber"),
                    fk("DEPARTMENT", "Mgr_ssn", "EMPLOYEE", "Ssn"),
                    fk("EMPLOYEE", "Super_ssn", "EMPLOYEE", "Ssn")),
            Map.of("EMPLOYEE", Map.of("Fname", "VARCHAR", "Ssn", "CHAR", "Bdate", "DATE", "Salary", "DECIMAL")),
            Map.of());

    private static final Fixture HOSPITAL = new Fixture(
            List.of(table("physicians", "physician_id", "full_name", "department"),
                    table("patients", "patient_id", "first_name", "last_name", "physician_id"),
                    table("wards", "ward_id", "name"),
                    table("admissions", "admission_id", "patient_id", "ward_id", "admission_date", "discharge_date")),
            List.of(fk("patients", "physician_id", "physicians", "physician_id"),
                    fk("admissions", "patient_id", "patients", "patient_id"),
                    fk("admissions", "ward_id", "wards", "ward_id")),
            Map.of("admissions", Map.of("admission_id", "INT", "admission_date", "DATE", "discharge_date", "DATE")),
            Map.of("physicians.department", List.of("Surgery", "Cardiology", "Pediatrics")));

    private static SchemaMetadata metadata(Dialect dialect, Map<String, List<String>> values) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        values.forEach((key, stored) -> {
            String[] parts = key.split("\\.");
            when(m.getDistinctValues(eq(parts[0]), eq(parts[1]))).thenReturn(stored);
        });
        return m;
    }

    private static Map<String, Map<String, String>> typesFor(Fixture fixture, Dialect dialect) {
        Map<String, Map<String, String>> types = new HashMap<>();
        fixture.types().forEach((tableName, columns) -> {
            Map<String, String> mapped = new HashMap<>();
            columns.forEach((column, type) -> mapped.put(column, dialect != Dialect.MONGODB ? type
                    : type.equals("DATE") ? "date" : type.equals("SMALLINT") ? "int" : "string"));
            types.put(tableName, mapped);
        });
        return types;
    }

    private static RuleBuildResult ask(Fixture fixture, String question, Dialect dialect) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        if (fixture != null) {
            schemas.publish(new SchemaSnapshot("f", "f.sql", "uploads", fixture.tables(), fixture.keys(), Map.of(),
                    Instant.now(), dialect, List.of(), typesFor(fixture, dialect)));
        }
        Map<String, List<String>> values = fixture == null ? Map.of() : fixture.values();
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL, values), metadata(Dialect.MONGODB, values))
                .parse(question, dialect);
    }

    static Stream<Arguments> rows() {
        Dialect sql = Dialect.MYSQL;
        Dialect mongo = Dialect.MONGODB;
        return Stream.of(
                // built-in shop demo
                of("shop", null, sql, "show all clients", "SELECT * FROM customers;"),
                of("shop", null, sql, "show the mail of shoppers", "SELECT email FROM customers;"),
                of("shop", null, sql, "show the cost of goods", "SELECT price FROM products;"),
                of("shop", null, sql, "show vendors", "SELECT * FROM suppliers;"),
                of("shop", null, sql, "show the staff", "SELECT * FROM employees;"),
                of("shop", null, sql, "show surgeons", DECLINE),
                of("shop", null, sql, "show faculty", DECLINE),
                of("shop", null, sql, "show the section of employees", DECLINE),
                of("shop", null, sql, "show customers admitted in 2024", DECLINE),

                // campus
                of("campus", CAMPUS, sql, "show all pupils", "SELECT * FROM students;"),
                of("campus", CAMPUS, sql, "list the teachers", "SELECT * FROM faculty;"),
                of("campus", CAMPUS, sql, "show the wage of offers", "SELECT ctc_lpa FROM offers;"),
                of("campus", CAMPUS, sql, "students admitted in 2024",
                        "SELECT * FROM students WHERE admission_year >= 2024 AND admission_year < 2025;"),
                of("campus", CAMPUS, sql, "pupils admitted since 2020",
                        "SELECT * FROM students WHERE admission_year >= 2020;"),
                of("campus", CAMPUS, sql, "students admitted last month", DECLINE),
                of("campus", CAMPUS, sql, "show undergraduates", "SELECT * FROM students WHERE level = 'Undergraduate';"),
                of("campus", CAMPUS, sql, "show modules", DECLINE),
                of("campus", CAMPUS, sql, "show sections", DECLINE),
                of("campus", CAMPUS, sql, "show abilities", DECLINE),
                of("campus", CAMPUS, mongo, "students admitted in 2024",
                        "db.students.find({\"$and\": [{\"admission_year\": {\"$gte\": 2024}}, "
                                + "{\"admission_year\": {\"$lt\": 2025}}]})"),
                of("campus", CAMPUS, mongo, "show undergraduates", "db.students.find({\"level\": \"Undergraduate\"})"),

                // company
                of("company", COMPANY, sql, "show the staff", "SELECT * FROM EMPLOYEE;"),
                of("company", COMPANY, sql, "show divisions", "SELECT * FROM DEPARTMENT;"),
                of("company", COMPANY, sql, "show the wage of employees", "SELECT Salary FROM EMPLOYEE;"),
                of("company", COMPANY, sql, "show employees born in 1985",
                        "SELECT * FROM EMPLOYEE WHERE Bdate >= '1985-01-01' AND Bdate < '1986-01-01';"),
                of("company", COMPANY, sql, "show employees born last month",
                        "SELECT * FROM EMPLOYEE WHERE Bdate >= '2026-09-01' AND Bdate < '2026-10-01';"),
                of("company", COMPANY, sql, "show sections", DECLINE),
                of("company", COMPANY, sql, "show surgeons", DECLINE),

                // hospital
                of("hospital", HOSPITAL, sql, "show doctors", "SELECT * FROM physicians;"),
                of("hospital", HOSPITAL, sql, "show the given name of patients", "SELECT first_name FROM patients;"),
                of("hospital", HOSPITAL, sql, "show cardiologists",
                        "SELECT * FROM physicians WHERE department = 'Cardiology';"),
                of("hospital", HOSPITAL, sql, "patients of cardiologists",
                        "SELECT DISTINCT patients.* FROM patients JOIN physicians "
                                + "ON patients.physician_id = physicians.physician_id "
                                + "WHERE physicians.department = 'Cardiology';"),
                of("hospital", HOSPITAL, sql, "show admissions admitted last month",
                        "SELECT * FROM admissions WHERE admission_date >= '2026-09-01' AND admission_date < '2026-10-01';"),
                of("hospital", HOSPITAL, sql, "show interns", DECLINE),
                of("hospital", HOSPITAL, sql, "show people", DECLINE),
                of("hospital", HOSPITAL, mongo, "show cardiologists", "db.physicians.find({\"department\": \"Cardiology\"})"),
                of("hospital", HOSPITAL, mongo, "show admissions admitted last month",
                        "db.admissions.find({\"admission_date\": {\"$gte\": ISODate(\"2026-08-31T18:30:00Z\"), "
                                + "\"$lt\": ISODate(\"2026-09-30T18:30:00Z\")}})"));
    }

    @ParameterizedTest(name = "{0}/{2}: {3}")
    @MethodSource("rows")
    @DisplayName("a reworded question builds the expected query, or declines")
    void rewordedQuestion(String name, Fixture fixture, Dialect dialect, String question, String expected) {
        RuleBuildResult result = ask(fixture, question, dialect);

        if (DECLINE.equals(expected)) {
            assertThat(result.built()).as(question + " -> " + result.sql()).isFalse();
            assertThat(result.trace().declineReason()).as(question).isNotNull();
        } else {
            assertThat(result.built()).as(question + " declined: " + result.trace().declineReason()).isTrue();
            assertThat(result.sql()).as(question).isEqualTo(expected);
        }
    }
}
