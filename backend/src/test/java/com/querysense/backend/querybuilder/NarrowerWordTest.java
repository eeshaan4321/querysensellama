package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A narrower word reads as its table filtered to the kind the stored data names. */
class NarrowerWordTest {

    private static final KnownSchema.Table PHYSICIANS =
            new KnownSchema.Table("physicians", List.of("physician_id", "full_name", "department"));
    private static final KnownSchema.Table PATIENTS =
            new KnownSchema.Table("patients", List.of("patient_id", "full_name", "physician_id"));
    private static final KnownSchema.Table WARDS = new KnownSchema.Table("wards", List.of("ward_id", "name"));
    private static final List<KnownSchema.ForeignKey> KEYS =
            List.of(new KnownSchema.ForeignKey("patients", "physician_id", "physicians", "physician_id"));
    private static final List<String> DEPARTMENTS = List.of("Surgery", "Cardiology", "Pediatrics");

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

    private static RuleBuildResult ask(String question, Dialect dialect, List<KnownSchema.Table> tables,
                                       Map<String, List<String>> values) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("h", "hospital.sql", "uploads", tables, KEYS, Map.of(), Instant.now(),
                dialect, List.of(), Map.of()));
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL, values), metadata(Dialect.MONGODB, values))
                .parse(question, dialect);
    }

    private static RuleBuildResult ask(String question) {
        return ask(question, Dialect.MYSQL, List.of(PHYSICIANS, PATIENTS, WARDS),
                Map.of("physicians.department", DEPARTMENTS));
    }

    @Test
    @DisplayName("surgeons: a narrower word reads as its table filtered by the stored kind")
    void surgeons() {
        RuleBuildResult result = ask("show surgeons");
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM physicians WHERE department = 'Surgery';");
        assertThat(result.interpretations()).contains(new Interpretation("surgeons",
                "physicians.department = 'Surgery'", SchemaLexicon.MatchKind.NARROWER));
    }

    @Test
    @DisplayName("surgeonsMongo: the same reading emits an equality filter for MongoDB")
    void surgeonsMongo() {
        RuleBuildResult result = ask("show surgeons", Dialect.MONGODB, List.of(PHYSICIANS, PATIENTS, WARDS),
                Map.of("physicians.department", DEPARTMENTS));
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("db.physicians.find({\"department\": \"Surgery\"})");
    }

    @Test
    @DisplayName("namedTableToo: the narrower word filters a table the question also names")
    void namedTableToo() {
        RuleBuildResult result = ask("physicians who are surgeons");
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM physicians WHERE department = 'Surgery';");
    }

    @Test
    @DisplayName("withJoin: the narrowed table joins to the named one")
    void withJoin() {
        RuleBuildResult result = ask("patients of surgeons");
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT DISTINCT patients.* FROM patients JOIN physicians "
                + "ON patients.physician_id = physicians.physician_id WHERE physicians.department = 'Surgery';");
    }

    @Test
    @DisplayName("noValueDeclines: no stored value names the kind")
    void noValueDeclines() {
        RuleBuildResult result = ask("show surgeons", Dialect.MYSQL, List.of(PHYSICIANS, PATIENTS, WARDS),
                Map.of("physicians.department", List.of("Cardiology", "Pediatrics")));
        assertThat(result.built()).isFalse();
    }

    @Test
    @DisplayName("twoColumnsDecline: two columns holding the kind decline")
    void twoColumnsDecline() {
        KnownSchema.Table physicians = new KnownSchema.Table("physicians",
                List.of("physician_id", "full_name", "department", "specialty"));
        RuleBuildResult result = ask("show surgeons", Dialect.MYSQL, List.of(physicians, PATIENTS, WARDS),
                Map.of("physicians.department", DEPARTMENTS, "physicians.specialty", List.of("Surgery", "Oncology")));
        assertThat(result.built()).isFalse();
    }

    @Test
    @DisplayName("partialValueDeclines: only a whole stored value counts")
    void partialValueDeclines() {
        RuleBuildResult result = ask("show surgeons", Dialect.MYSQL, List.of(PHYSICIANS, PATIENTS, WARDS),
                Map.of("physicians.department", List.of("General Surgery", "Cardiology")));
        assertThat(result.built()).isFalse();
    }

    @Test
    @DisplayName("narrowerInTwoTablesDeclines: a kind of two tables holding it declines")
    void narrowerInTwoTablesDeclines() {
        List<KnownSchema.Table> tables = new ArrayList<>(List.of(PHYSICIANS, PATIENTS, WARDS));
        tables.add(new KnownSchema.Table("doctors", List.of("doctor_id", "full_name", "department")));
        RuleBuildResult result = ask("show surgeons", Dialect.MYSQL, tables,
                Map.of("physicians.department", DEPARTMENTS, "doctors.department", List.of("Surgery")));
        assertThat(result.built()).isFalse();
    }

    @Test
    @DisplayName("narrowerUnjoinableDeclines: no key between the tables declines as before")
    void narrowerUnjoinableDeclines() {
        RuleBuildResult result = ask("wards with surgeons");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("declares no relationship");
    }

    @Test
    @DisplayName("broaderNeverLinks: a broader word never reaches a table")
    void broaderNeverLinks() {
        RuleBuildResult result = ask("show people");
        assertThat(result.built()).isFalse();
    }

    @Test
    @DisplayName("twoNarrowerWordsDecline: two narrower words are not combined")
    void twoNarrowerWordsDecline() {
        RuleBuildResult result = ask("show surgeons and cardiologists");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("surgeons").contains("cardiologists");
    }

    private static RuleBuildResult askWide(String question) {
        KnownSchema.Table physicians = new KnownSchema.Table("physicians",
                List.of("physician_id", "full_name", "department", "salary", "city"));
        return ask(question, Dialect.MYSQL, List.of(physicians, PATIENTS, WARDS),
                Map.of("physicians.department", DEPARTMENTS, "physicians.city", List.of("London")));
    }

    @Test
    @DisplayName("orAcrossConditionsDeclines: a narrowing is never ORed with the question's conditions")
    void orAcrossConditionsDeclines() {
        for (String question : List.of("show surgeons with salary over 100 or salary under 5",
                "show surgeons in london or with salary over 100")) {
            RuleBuildResult result = askWide(question);
            assertThat(result.built()).as(question + " -> " + result.sql()).isFalse();
            assertThat(result.trace().declineReason()).as(question).contains("\"or\"").contains("surgeons");
        }
    }

    @Test
    @DisplayName("deleteDeclines: a narrowing never chooses rows to delete")
    void deleteDeclines() {
        for (String question : List.of("delete surgeons", "delete surgeons with salary over 100")) {
            RuleBuildResult result = askWide(question);
            assertThat(result.built()).as(question + " -> " + result.sql()).isFalse();
            assertThat(result.trace().declineReason()).as(question).contains("rows to delete");
        }
    }

    @Test
    @DisplayName("sameColumnFilterDeclines: another filter on the narrowed column declines")
    void sameColumnFilterDeclines() {
        for (String question : List.of("show surgeons in cardiology", "show physicians in cardiology who are surgeons")) {
            RuleBuildResult result = askWide(question);
            assertThat(result.built()).as(question + " -> " + result.sql()).isFalse();
            assertThat(result.trace().declineReason()).as(question).contains("physicians.department another way");
        }
    }

    @Test
    @DisplayName("otherColumnFilterBuilds: a filter on a different column combines with the narrowing")
    void otherColumnFilterBuilds() {
        RuleBuildResult result = askWide("surgeons with salary over 100");
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM physicians WHERE department = 'Surgery' AND salary > 100;");
    }

    @Test
    @DisplayName("negationDeclines: a narrowing is never read alongside a negation")
    void negationDeclines() {
        for (String question : List.of("physicians who are not cardiologists", "patients not of surgeons",
                "physicians except surgeons", "physicians other than surgeons", "physicians who aren't surgeons",
                "show non surgeons", "show all physicians but surgeons", "physicians excluding surgeons")) {
            for (Dialect dialect : Dialect.values()) {
                RuleBuildResult result = ask(question, dialect, List.of(PHYSICIANS, PATIENTS, WARDS),
                        Map.of("physicians.department", DEPARTMENTS));
                assertThat(result.built()).as(dialect + " " + question + " -> " + result.sql()).isFalse();
                assertThat(result.trace().declineReason()).as(question).contains("is read as a kind of physicians");
            }
        }
    }

    @Test
    @DisplayName("narrowedSubjectFirst: a narrower word said first stays the subject")
    void narrowedSubjectFirst() {
        for (String question : List.of("surgeons with patients", "show surgeons and their patients")) {
            RuleBuildResult named = ask(question.replace("surgeons", "physicians"));
            RuleBuildResult result = ask(question);
            assertThat(named.built()).as(named.trace().declineReason()).isTrue();
            assertThat(result.built()).as(result.trace().declineReason()).isTrue();
            assertThat(result.sql()).as(question).isEqualTo(
                    named.sql().replace(";", " WHERE physicians.department = 'Surgery';"));
        }
    }

    @Test
    @DisplayName("narrowedSubjectFirstMongo: the narrowed collection stays the subject for MongoDB")
    void narrowedSubjectFirstMongo() {
        RuleBuildResult result = ask("surgeons with patients", Dialect.MONGODB, List.of(PHYSICIANS, PATIENTS, WARDS),
                Map.of("physicians.department", DEPARTMENTS));
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).startsWith("db.physicians.").contains("\"department\": \"Surgery\"");
    }

    @Test
    @DisplayName("antiJoinDeclines: a narrowing is never combined with a check that something is missing")
    void antiJoinDeclines() {
        for (String question : List.of("surgeons without patients", "surgeons with zero patients",
                "patients without surgeons")) {
            for (Dialect dialect : Dialect.values()) {
                RuleBuildResult result = ask(question, dialect, List.of(PHYSICIANS, PATIENTS, WARDS),
                        Map.of("physicians.department", DEPARTMENTS));
                assertThat(result.built()).as(dialect + " " + question + " -> " + result.sql()).isFalse();
            }
        }
    }

    private static final KnownSchema.Table WORKERS =
            new KnownSchema.Table("workers", List.of("worker_id", "last_name", "role"));

    private static RuleBuildResult askWorkers(String question) {
        return ask(question, Dialect.MYSQL, List.of(WORKERS),
                Map.of("workers.last_name", List.of("Cook", "Smith"), "workers.role", List.of("Clerk", "Driver")));
    }

    @Test
    @DisplayName("nameColumnNeverNarrows: a surname that happens to name a kind is not a filter")
    void nameColumnNeverNarrows() {
        RuleBuildResult result = askWorkers("show cooks");
        assertThat(result.built()).as(result.sql()).isFalse();
    }

    @Test
    @DisplayName("abbreviatedNameColumnNeverNarrows: Lname is a name column too")
    void abbreviatedNameColumnNeverNarrows() {
        RuleBuildResult result = ask("show clerks", Dialect.MYSQL,
                List.of(new KnownSchema.Table("EMPLOYEE", List.of("Ssn", "Fname", "Lname"))),
                Map.of("EMPLOYEE.Lname", List.of("Clerk", "Smith")));
        assertThat(result.built()).as(result.sql()).isFalse();
    }

    @Test
    @DisplayName("roleColumnNarrows: a category column naming the kind still narrows")
    void roleColumnNarrows() {
        RuleBuildResult result = askWorkers("show clerks");
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM workers WHERE role = 'Clerk';");
    }

    @Test
    @DisplayName("mostlyDistinctColumnNeverNarrows: a column whose values are mostly distinct is not a category")
    void mostlyDistinctColumnNeverNarrows() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("h", "hospital.sql", "uploads", List.of(PHYSICIANS, PATIENTS, WARDS), KEYS,
                Map.of(), Instant.now(), Dialect.MYSQL, List.of(), Map.of()));
        SchemaMetadata mysql = metadata(Dialect.MYSQL, Map.of("physicians.department", DEPARTMENTS));
        when(mysql.distinctShare("physicians", "department")).thenReturn(OptionalDouble.of(0.9));
        RuleBuildResult result = TestParsers.parser(schemas, mysql, metadata(Dialect.MONGODB, Map.of()))
                .parse("show surgeons", Dialect.MYSQL);
        assertThat(result.built()).as(result.sql()).isFalse();
    }
}
