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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dictionary synonyms reach tables and columns only where nothing stricter applies. */
class ThesaurusLexiconTest {

    private static KnownSchema.Table table(String name, String... columns) {
        return new KnownSchema.Table(name, List.of(columns));
    }

    private static ActiveSchemaProvider schema(Dialect dialect, KnownSchema.Table... tables) {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("u", "u.sql", "querysense_uploads", List.of(tables), List.of(),
                Map.of(), Instant.now(), dialect, List.of()));
        return schemas;
    }

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        return m;
    }

    private static RuleBuildResult ask(ActiveSchemaProvider schemas, String question, Dialect dialect) {
        return TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB)).parse(question, dialect);
    }

    private static RuleBuildResult ask(ActiveSchemaProvider schemas, String question) {
        return ask(schemas, question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("a dictionary synonym reaches a table that no curated list names")
    void carsReachAutos() {
        RuleBuildResult result = ask(schema(Dialect.MYSQL, table("autos", "auto_id", "plate")), "show all cars");

        assertThat(result.trace().declineReason()).isNull();
        assertThat(result.sql()).isEqualTo("SELECT * FROM autos;");
        assertThat(result.interpretations()).extracting(Interpretation::kind)
                .contains(SchemaLexicon.MatchKind.THESAURUS);
        assertThat(result.interpreted()).isTrue();
    }

    @Test
    @DisplayName("a curated synonym still reads pupils as students")
    void pupilsReachStudents() {
        RuleBuildResult result = ask(schema(Dialect.MYSQL, table("students", "student_id", "full_name")), "show all pupils");

        assertThat(result.sql()).isEqualTo("SELECT * FROM students;");
    }

    @Test
    @DisplayName("a dictionary synonym reaches a column")
    void givenNameReachesFirstName() {
        RuleBuildResult result = ask(
                schema(Dialect.MYSQL, table("people", "person_id", "first_name", "last_name")),
                "show the given name of people");

        assertThat(result.sql()).isEqualTo("SELECT first_name FROM people;");
        assertThat(result.interpretations()).extracting(Interpretation::kind)
                .contains(SchemaLexicon.MatchKind.THESAURUS);
    }

    @Test
    @DisplayName("commands is not read as orders")
    void ordersNotCommands() {
        RuleBuildResult result = ask(new ActiveSchemaProvider(), "show commands");

        assertThat(result.sql()).isNull();
        assertThat(result.trace().declineReason()).isNotNull();
    }

    @Test
    @DisplayName("classes is not read as courses")
    void classNotCourses() {
        RuleBuildResult result = ask(schema(Dialect.MYSQL, table("courses", "course_id", "title")), "show classes");

        assertThat(result.sql()).isNull();
        assertThat(result.trace().declineReason()).isNotNull();
    }

    @Test
    @DisplayName("a table with the exact name beats a dictionary synonym of another")
    void stricterMatchWins() {
        RuleBuildResult result = ask(
                schema(Dialect.MYSQL, table("students", "student_id"), table("pupils", "pupil_id")), "show pupils");

        assertThat(result.sql()).isEqualTo("SELECT * FROM pupils;");
    }

    @Test
    @DisplayName("two tables reachable through the dictionary decline")
    void twoTablesDecline() {
        RuleBuildResult result = ask(
                schema(Dialect.MYSQL, table("autos", "auto_id"), table("motorcars", "motorcar_id")), "show all cars");

        assertThat(result.sql()).isNull();
        assertThat(result.trace().declineReason()).contains("could mean");
    }

    @Test
    @DisplayName("MongoDB reads the same dictionary synonym")
    void mongoParity() {
        RuleBuildResult result = ask(schema(Dialect.MONGODB, table("autos", "_id", "plate")), "show all cars", Dialect.MONGODB);

        assertThat(result.sql()).isEqualTo("db.autos.find({})");
    }

    @Test
    @DisplayName("an ambiguous exact match is not overridden by a dictionary link to another column")
    void ambiguityBeatsDictionary() {
        RuleBuildResult result = ask(
                schema(Dialect.MYSQL, table("contracts", "contract_id", "base_remuneration", "bonus_remuneration", "salary")),
                "show the remuneration of contracts");

        assertThat(result.sql()).isNull();
        assertThat(result.trace().declineReason()).contains("could mean");
    }

    @Test
    @DisplayName("denied dictionary links never reach a table or column")
    void deniedLinksDecline() {
        ActiveSchemaProvider company = schema(Dialect.MYSQL,
                table("EMPLOYEE", "Fname", "Lname", "Ssn", "Sex", "Salary", "Dno"),
                table("DEPARTMENT", "Dname", "Dnumber"),
                table("PROJECT", "Pname", "Pnumber", "Plocation", "Dnum"),
                table("WORKS_ON", "Essn", "Pno", "Hours"));
        for (String question : List.of("show tasks", "show all undertakings", "show the sexual activity of employee")) {
            RuleBuildResult result = ask(company, question);
            assertThat(result.sql()).as(question).isNull();
            assertThat(result.trace().declineReason()).as(question).isNotNull();
        }
    }
}
