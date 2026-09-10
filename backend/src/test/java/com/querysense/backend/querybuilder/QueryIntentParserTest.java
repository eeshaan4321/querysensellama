package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
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

class QueryIntentParserTest {

    private QueryIntentParser parser;
    private ActiveSchemaProvider activeSchema;

    /**
     * A schema where "name" is genuinely ambiguous — modelled on the
     * movie_rentals upload that exposed this, where `customers` has
     * first_name and last_name and no `name` at all.
     */
    private static SchemaSnapshot twoNameSchema() {
        return new SchemaSnapshot(
                "upload-names", "movie_rentals.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers",
                        List.of("customer_id", "first_name", "last_name", "city", "joined_date"))),
                List.of(), Map.of("customers", 12000), Instant.now());
    }

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getDistinctValues(eq("customers"), eq("city")))
                .thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(metadata.getColumnNames(anyString())).thenReturn(List.of());

        activeSchema = new ActiveSchemaProvider();
        parser = new QueryIntentParser(
                new SchemaBinder(activeSchema),
                new JoinPathResolver(activeSchema),
                metadata,
                new SqlEmitter());
    }

    @Test
    @DisplayName("the sample prompt that was LLM-only now builds a join with HAVING")
    void buildsJoinWithHaving() {
        RuleBuildResult result = parser.parse("Which customers placed more than three orders");

        assertThat(result.built()).isTrue();
        assertThat(result.sql())
                .contains("JOIN orders ON customers.id = orders.customer_id")
                .contains("GROUP BY")
                .contains("HAVING COUNT(orders.id) > 3");
        assertThat(result.trace().joinEdge().origin()).isEqualTo("declared foreign key");
    }

    @Test
    @DisplayName("a question word alone no longer forces a decline")
    void questionWordIsNotComplexity() {
        RuleBuildResult result = parser.parse("which customers are in mumbai");

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("the filter column is found in the data, not hardcoded to city")
    void filterColumnComesFromTheData() {
        RuleBuildResult result = parser.parse("customers in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.trace().bindings())
                .anySatisfy(b -> assertThat(b.resolvedTo()).isEqualTo("customers.city"));
    }

    @Test
    @DisplayName("a value present in no column of the bound table declines")
    void ungroundedValueDeclines() {
        RuleBuildResult result = parser.parse("customers in atlantis");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).isNotBlank();
    }

    @Test
    @DisplayName("per <column> becomes a real GROUP BY instead of being dropped")
    void perColumnGroups() {
        RuleBuildResult result = parser.parse("how many orders are there per status");

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("GROUP BY status").contains("COUNT(");
    }

    @Test
    @DisplayName("a temporal expression declines, but the word 'may' alone does not")
    void temporalDeclinesButModalMayDoesNot() {
        assertThat(parser.parse("orders from last month").built()).isFalse();
        assertThat(parser.parse("show all customers").built()).isTrue();
    }

    @Test
    @DisplayName("two tables with no declared relationship decline")
    void unrelatedPairDeclines() {
        RuleBuildResult result = parser.parse("customers and suppliers");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("relationship");
    }

    @Test
    @DisplayName("the built-in sample prompts are unchanged")
    void builtInSamplePromptsUnchanged() {
        assertThat(parser.parse("Show all customers").sql()).isEqualTo("SELECT * FROM customers;");
        assertThat(parser.parse("Show only the name and email of customers").sql())
                .isEqualTo("SELECT name, email FROM customers;");
        assertThat(parser.parse("How many orders are there").sql())
                .isEqualTo("SELECT COUNT(*) FROM orders;");
    }

    @Test
    @DisplayName("a bare '<table> <column>' compound projects that column")
    void trailingCompoundProjectsTheColumn() {
        assertThat(parser.parse("customer name").sql())
                .isEqualTo("SELECT name FROM customers;");
        assertThat(parser.parse("customer city").sql())
                .isEqualTo("SELECT city FROM customers;");
        assertThat(parser.parse("show customer phone").sql())
                .isEqualTo("SELECT phone FROM customers;");
    }

    @Test
    @DisplayName("a multi-word compound binds through underscore normalisation")
    void multiWordCompoundBinds() {
        activeSchema.publish(twoNameSchema());

        assertThat(parser.parse("customer first name").sql())
                .isEqualTo("SELECT first_name FROM customers;");
    }

    @Test
    @DisplayName("a compound that resolves to two columns declines instead of picking one")
    void ambiguousCompoundDeclines() {
        activeSchema.publish(twoNameSchema());

        // `name` could be first_name or last_name. Silently choosing one is
        // the confident-wrong-answer failure; SELECT * is the silently
        // incomplete one. Neither is acceptable, so it defers.
        RuleBuildResult result = parser.parse("customer name");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).isNotBlank();
    }

    @Test
    @DisplayName("a prepositional tail is not a projection attempt")
    void prepositionalTailIsNotAProjection() {
        // "customers in pune" must keep its WHERE and stay SELECT * — the
        // old engine's own note: most sentences legitimately have a
        // non-column word after the table noun.
        assertThat(parser.parse("customers in pune").sql())
                .isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(parser.parse("show all customers").sql())
                .isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("an aggregate over an unbindable column declines rather than guessing")
    void unbindableAggregateColumnDeclines() {
        assertThat(parser.parse("average of the flurb of customers").built()).isFalse();
    }

    @Test
    @DisplayName("a comparison on a real column becomes a WHERE, not a HAVING")
    void plainComparisonBecomesWhere() {
        RuleBuildResult result = parser.parse("products with price above 100");

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM products WHERE price > 100;");
    }
}
