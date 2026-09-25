package com.querysense.backend.optimization;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.tables()).thenReturn(KnownSchema.TABLES);
        when(activeSchema.findTable(anyString())).thenAnswer(call ->
                KnownSchema.TABLES.stream()
                        .filter(t -> t.name().equalsIgnoreCase(call.getArgument(0)))
                        .findFirst());
        rewriter = new QueryRewriter(activeSchema);
    }

    // ---- RW1: SELECT * becomes the schema's column list ----

    @Test
    @DisplayName("RW1 expands SELECT * to the columns the active schema declares")
    void expandsSelectStar() {
        QueryRewrite result = rewriter.rewrite("SELECT * FROM customers WHERE city = 'mumbai';", "MySQL");

        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("id, name, email, city, phone");
        assertThat(result.sql()).doesNotContain("*");
        assertThat(result.changes()).extracting(RewriteChange::ruleId).contains("RW1");
    }

    @Test
    @DisplayName("RW1 declines when the table is not in the active schema")
    void leavesSelectStarOnUnknownTable() {
        QueryRewrite result = rewriter.rewrite("SELECT * FROM transactions;", "MySQL");

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo("SELECT * FROM transactions;");
    }

    @Test
    @DisplayName("RW1 declines on a join, where * spans more than one table")
    void leavesSelectStarOnJoin() {
        QueryRewrite result = rewriter.rewrite(
                "SELECT * FROM customers c JOIN orders o ON c.id = o.customer_id;", "MySQL");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).doesNotContain("RW1");
    }

    // ---- RW2: an OR chain on one column becomes IN ----

    @Test
    @DisplayName("RW2 collapses an OR chain on one column into IN")
    void collapsesOrChainToIn() {
        QueryRewrite result = rewriter.rewrite(
                "SELECT id FROM customers WHERE city = 'mumbai' OR city = 'pune' OR city = 'delhi';",
                "MySQL");

        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("city IN ('mumbai', 'pune', 'delhi')");
        assertThat(result.changes()).extracting(RewriteChange::ruleId).contains("RW2");
    }

    @Test
    @DisplayName("RW2 leaves an OR across two different columns alone")
    void leavesOrAcrossColumns() {
        String sql = "SELECT id FROM customers WHERE city = 'mumbai' OR name = 'asha';";
        QueryRewrite result = rewriter.rewrite(sql, "MySQL");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).doesNotContain("RW2");
    }

    @Test
    @DisplayName("RW2 leaves a single equality alone rather than writing a one-element IN")
    void leavesSingleEquality() {
        QueryRewrite result = rewriter.rewrite("SELECT id FROM customers WHERE city = 'mumbai';", "MySQL");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).doesNotContain("RW2");
    }

    // ---- RW3: a pointless sort inside a bare aggregate ----

    @Test
    @DisplayName("RW3 drops ORDER BY from a bare COUNT(*) with no GROUP BY")
    void dropsOrderByFromBareAggregate() {
        QueryRewrite result = rewriter.rewrite(
                "SELECT COUNT(*) FROM customers ORDER BY name;", "MySQL");

        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).doesNotContainIgnoringCase("order by");
        assertThat(result.changes()).extracting(RewriteChange::ruleId).contains("RW3");
    }

    @Test
    @DisplayName("RW3 keeps ORDER BY when there is a GROUP BY, where it still orders the groups")
    void keepsOrderByWithGroupBy() {
        QueryRewrite result = rewriter.rewrite(
                "SELECT city, COUNT(*) FROM customers GROUP BY city ORDER BY city;", "MySQL");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).doesNotContain("RW3");
    }

    @Test
    @DisplayName("RW3 keeps ORDER BY on a non-aggregate query, where it is the point")
    void keepsOrderByOnPlainSelect() {
        QueryRewrite result = rewriter.rewrite("SELECT name FROM customers ORDER BY name;", "MySQL");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).doesNotContain("RW3");
    }

    // ---- boundaries ----

    @Test
    @DisplayName("a query with nothing to improve comes back unchanged and says so")
    void unchangedQueryReportsNoChange() {
        String sql = "SELECT id FROM customers WHERE city = 'mumbai';";
        QueryRewrite result = rewriter.rewrite(sql, "MySQL");

        assertThat(result.changed()).isFalse();
        assertThat(result.changes()).isEmpty();
        assertThat(result.sql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("unparseable SQL is returned untouched rather than mangled")
    void unparseableSqlIsUntouched() {
        String sql = "SELCT * FRM customers";
        QueryRewrite result = rewriter.rewrite(sql, "MySQL");

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("MongoDB is declined outright — this rewriter only knows SQL")
    void mongoIsDeclined() {
        String query = "db.customers.find({ city: 'mumbai' })";
        QueryRewrite result = rewriter.rewrite(query, "MongoDB");

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo(query);
    }

    @Test
    @DisplayName("a write statement is never rewritten")
    void writeStatementIsUntouched() {
        String sql = "DELETE FROM customers WHERE city = 'mumbai';";
        QueryRewrite result = rewriter.rewrite(sql, "MySQL");

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("two rules can apply to one query")
    void rulesCompose() {
        QueryRewrite result = rewriter.rewrite(
                "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';", "MySQL");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).contains("RW1", "RW2");
        assertThat(result.sql()).contains("city IN ('mumbai', 'pune')");
        assertThat(result.sql()).doesNotContain("*");
    }
}
