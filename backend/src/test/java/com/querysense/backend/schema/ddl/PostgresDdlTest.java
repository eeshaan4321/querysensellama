package com.querysense.backend.schema.ddl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.upload.SchemaUploadValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresDdlTest {

    private final DdlSchemaParser parser = new DdlSchemaParser();

    private ParsedColumn column(ParsedSchema schema, String table, String column) {
        return schema.table(table).orElseThrow().column(column).orElseThrow();
    }

    @Test
    @DisplayName("referential actions and DEFERRABLE no longer drop a table; its foreign keys are kept")
    void referentialActions() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE departments (
                    dept_id INT PRIMARY KEY,
                    parent_dept_id INT REFERENCES departments(dept_id) ON DELETE SET NULL,
                    head_id INT REFERENCES departments(dept_id) ON UPDATE SET DEFAULT DEFERRABLE INITIALLY DEFERRED
                );
                """);

        assertThat(schema.warnings()).isEmpty();
        assertThat(schema.allForeignKeys()).containsExactly(
                new KnownSchema.ForeignKey("departments", "parent_dept_id", "departments", "dept_id"),
                new KnownSchema.ForeignKey("departments", "head_id", "departments", "dept_id"));
    }

    @Test
    @DisplayName("an EXCLUDE constraint is left out and the table is kept")
    void excludeConstraint() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE terms (
                    term_id INT PRIMARY KEY,
                    start_date DATE NOT NULL,
                    end_date DATE NOT NULL,
                    EXCLUDE USING gist (daterange(start_date, end_date, '[]') WITH &&)
                );
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.table("terms").orElseThrow().columnNames()).containsExactly("term_id", "start_date", "end_date");
    }

    @Test
    @DisplayName("identity and serial columns become auto-increment integers")
    void identityAndSerial() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE a (id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, label TEXT);
                CREATE TABLE b (id SERIAL PRIMARY KEY, big BIGSERIAL);
                """);

        assertThat(column(schema, "a", "id").autoIncrement()).isTrue();
        assertThat(column(schema, "a", "id").normalisedType()).isEqualTo("INT");
        assertThat(column(schema, "b", "id").normalisedType()).isEqualTo("INT");
        assertThat(column(schema, "b", "id").autoIncrement()).isTrue();
        assertThat(column(schema, "b", "big").normalisedType()).isEqualTo("BIGINT");
    }

    @Test
    @DisplayName("a computed column is left out with a warning naming it")
    void computedColumn() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE slots (
                    slot_id INT PRIMARY KEY,
                    start_time TIME NOT NULL,
                    end_time TIME NOT NULL,
                    slot_range TSRANGE GENERATED ALWAYS AS
                        (tsrange(DATE '2000-01-01' + start_time, DATE '2000-01-01' + end_time)) STORED
                );
                """);

        assertThat(schema.table("slots").orElseThrow().columnNames()).containsExactly("slot_id", "start_time", "end_time");
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("slots.slot_range"));
    }

    @Test
    @DisplayName("PostgreSQL types become MySQL types, with a warning for those stored as text")
    void types() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE t (
                    id INT PRIMARY KEY,
                    at TIMESTAMPTZ,
                    score DOUBLE PRECISION,
                    payload JSONB,
                    token UUID,
                    active BOOLEAN,
                    note TEXT
                );
                """);

        assertThat(column(schema, "t", "at").normalisedType()).isEqualTo("TIMESTAMP");
        assertThat(column(schema, "t", "score").normalisedType()).isEqualTo("DOUBLE");
        assertThat(column(schema, "t", "payload").normalisedType()).isEqualTo("TEXT");
        assertThat(column(schema, "t", "token").normalisedType()).isEqualTo("CHAR");
        assertThat(column(schema, "t", "token").typeArgs()).containsExactly("36");
        assertThat(column(schema, "t", "active").normalisedType()).isEqualTo("BOOLEAN");
        assertThat(column(schema, "t", "note").normalisedType()).isEqualTo("TEXT");
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("t.payload").contains("JSONB"));
    }

    @Test
    @DisplayName("TEXT used as a key, unique value or foreign key on either side becomes VARCHAR(255)")
    void keyedText() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE grade_scale (letter TEXT PRIMARY KEY, points NUMERIC(3,2));
                CREATE TABLE departments (dept_id INT PRIMARY KEY, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL);
                CREATE TABLE enrollments (id INT PRIMARY KEY, grade_letter TEXT REFERENCES grade_scale(letter));
                """);

        assertThat(column(schema, "grade_scale", "letter").normalisedType()).isEqualTo("VARCHAR");
        assertThat(column(schema, "grade_scale", "letter").typeArgs()).containsExactly("255");
        assertThat(column(schema, "departments", "code").normalisedType()).isEqualTo("VARCHAR");
        assertThat(column(schema, "enrollments", "grade_letter").normalisedType()).isEqualTo("VARCHAR");
        assertThat(column(schema, "departments", "name").normalisedType()).isEqualTo("TEXT");
    }

    @Test
    @DisplayName("a foreign key to a table that could not be read names that table and why")
    void unreadableTargetIsNamed() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE departments (dept_id INT PRIMARY KEY, x INT CHECK (((;
                CREATE TABLE faculty (faculty_id INT PRIMARY KEY, dept_id INT REFERENCES departments(dept_id));
                """);

        assertThatThrownBy(() -> new SchemaUploadValidator(40).validate(schema, Dialect.MYSQL))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("'departments'")
                .hasMessageContaining("could not be read");
    }

    @Test
    @DisplayName("function bodies in dollar quotes do not break the statements around them")
    void dollarQuotedBodies() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE a (id INT PRIMARY KEY);
                CREATE FUNCTION f() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.id < 0 THEN RAISE EXCEPTION 'it''s negative; no'; END IF;
                  RETURN NEW;
                END $$;
                DO $body$ BEGIN PERFORM 1; END $body$;
                CREATE TABLE b (id INT PRIMARY KEY, a_id INT REFERENCES a(id));
                """);

        assertThat(schema.tables()).extracting(ParsedTable::name).containsExactly("a", "b");
        assertThat(schema.warnings()).isEmpty();
    }
}
