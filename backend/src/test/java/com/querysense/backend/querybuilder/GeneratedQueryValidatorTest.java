package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedQueryValidatorTest {

    private GeneratedQueryValidator validator;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);

        when(activeSchema.tables(Dialect.MYSQL)).thenReturn(KnownSchema.TABLES);
        when(activeSchema.findTable(eq(Dialect.MYSQL), anyString())).thenAnswer(invocation ->
                KnownSchema.TABLES.stream()
                        .filter(t -> t.name().equalsIgnoreCase(invocation.getArgument(1)))
                        .findFirst());

        validator = new GeneratedQueryValidator(activeSchema);
    }

    @Test
    @DisplayName("a table outside the active schema is named in the reason")
    void unknownTableIsReported() {
        Optional<String> reason = validator.namesUnknownIdentifiers(
                "SELECT buyer_id, SUM(amount) FROM transactions GROUP BY buyer_id;", "MySQL");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("transactions");
    }

    @Test
    @DisplayName("a column the table does not have is named in the reason")
    void unknownColumnIsReported() {
        Optional<String> reason = validator.namesUnknownIdentifiers(
                "SELECT id FROM customers WHERE created_at > '2026-01-01';", "MySQL");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("created_at").contains("customers");
    }

    @Test
    @DisplayName("a query that matches the schema passes")
    void conformingQueryPasses() {
        Optional<String> reason = validator.namesUnknownIdentifiers(
                "SELECT name, city FROM customers WHERE city = 'mumbai';", "MySQL");

        assertThat(reason).isEmpty();
    }

    @Test
    @DisplayName("a join with qualified columns and aliases passes")
    void aliasedJoinPasses() {
        Optional<String> reason = validator.namesUnknownIdentifiers(
                "SELECT c.name, SUM(p.amount) FROM customers c "
                        + "JOIN orders o ON c.id = o.customer_id "
                        + "JOIN payments p ON p.order_id = o.id GROUP BY c.name;", "MySQL");

        assertThat(reason).isEmpty();
    }

    @Test
    @DisplayName("SELECT * names no columns, so there is nothing to disprove")
    void selectStarPasses() {
        Optional<String> reason = validator.namesUnknownIdentifiers(
                "SELECT * FROM orders;", "MySQL");

        assertThat(reason).isEmpty();
    }

    @Test
    @DisplayName("an alias qualifying a column that its own table lacks is reported")
    void wrongTableForQualifiedColumnIsReported() {
        Optional<String> reason = validator.namesUnknownIdentifiers(
                "SELECT c.amount FROM customers c JOIN orders o ON c.id = o.customer_id;", "MySQL");

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("amount");
    }

    @Test
    @DisplayName("SQL this parser cannot read is not held against the model")
    void unparseableSqlPasses() {
        Optional<String> reason = validator.namesUnknownIdentifiers("not sql at all", "MySQL");

        assertThat(reason).isEmpty();
    }

    @Test
    @DisplayName("an empty active schema cannot disprove anything")
    void emptySchemaPasses() {
        ActiveSchemaProvider empty = mock(ActiveSchemaProvider.class);
        when(empty.tables(Dialect.MYSQL)).thenReturn(List.of());
        when(empty.findTable(eq(Dialect.MYSQL), anyString())).thenReturn(Optional.empty());

        Optional<String> reason = new GeneratedQueryValidator(empty)
                .namesUnknownIdentifiers("SELECT id FROM customers;", "MySQL");

        assertThat(reason).isEmpty();
    }

    @Test
    @DisplayName("a DELETE or UPDATE with no WHERE, a DROP or a TRUNCATE is flagged as unsafe")
    void statementsThatTouchEveryRowAreUnsafe() {
        assertThat(validator.unsafeStatement("DELETE FROM DEPENDENT;", "MySQL"))
                .get().asString().contains("DEPENDENT").contains("every row");
        assertThat(validator.unsafeStatement("UPDATE orders SET status = 'PAID';", "MySQL"))
                .get().asString().contains("orders").contains("every row");
        assertThat(validator.unsafeStatement("DROP TABLE orders;", "MySQL")).isPresent();
        assertThat(validator.unsafeStatement("TRUNCATE TABLE orders;", "MySQL")).isPresent();
    }

    @Test
    @DisplayName("a DELETE with a WHERE, and any SELECT, are not unsafe")
    void qualifiedStatementsAreSafe() {
        assertThat(validator.unsafeStatement("DELETE FROM orders WHERE status = 'CANCELLED';", "MySQL")).isEmpty();
        assertThat(validator.unsafeStatement("SELECT * FROM orders;", "MySQL")).isEmpty();
        assertThat(validator.unsafeStatement("not sql at all", "MySQL")).isEmpty();
    }

    @Test
    @DisplayName("MongoDB: deleteMany({}), remove({}) and drop() are flagged; a filtered delete is not")
    void mongoStatementsThatTouchEveryDocumentAreUnsafe() {
        assertThat(validator.unsafeStatement("db.dependents.deleteMany({})", "MongoDB"))
                .get().asString().contains("dependents");
        assertThat(validator.unsafeStatement("db.dependents.remove({ })", "MongoDB")).isPresent();
        assertThat(validator.unsafeStatement("db.dependents.drop()", "MongoDB")).isPresent();
        assertThat(validator.unsafeStatement("db.dependents.updateMany({}, {\"$set\": {\"a\": 1}})", "MongoDB")).isPresent();
        assertThat(validator.unsafeStatement("db.dependents.deleteMany({\"relationship\": \"son\"})", "MongoDB")).isEmpty();
        assertThat(validator.unsafeStatement("db.dependents.find({})", "MongoDB")).isEmpty();
    }

    @Test
    @DisplayName("MongoDB: a bare deleteMany({}) with no db.<collection> prefix is still flagged")
    void bareMongoDeleteManyIsUnsafe() {
        assertThat(validator.unsafeStatement("deleteMany({})", "MongoDB"))
                .get().asString().doesNotContain("null");
        assertThat(validator.unsafeStatement("drop()", "MongoDB")).isPresent();
        assertThat(validator.unsafeStatement("deleteMany({\"status\": \"PENDING\"})", "MongoDB")).isEmpty();
    }
}
