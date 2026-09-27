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

class QueryIntentParserTest {

    private QueryIntentParser parser;
    private ActiveSchemaProvider activeSchema;
    private SchemaMetadata metadata;

    private static SchemaSnapshot twoNameSchema() {
        return new SchemaSnapshot(
                "upload-names", "movie_rentals.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers",
                        List.of("customer_id", "first_name", "last_name", "city", "joined_date"))),
                List.of(), Map.of("customers", 12000), Instant.now());
    }

    @BeforeEach
    void setUp() {
        metadata = mock(SchemaMetadata.class);
        when(metadata.dialect()).thenReturn(Dialect.MYSQL);
        SchemaMetadata mongoMetadata = mock(SchemaMetadata.class);
        when(mongoMetadata.dialect()).thenReturn(Dialect.MONGODB);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getDistinctValues(eq("customers"), eq("city")))
                .thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(metadata.getColumnNames(anyString())).thenReturn(List.of());

        activeSchema = new ActiveSchemaProvider();
        parser = TestParsers.parser(activeSchema, metadata, mongoMetadata);
    }

    @Test
    @DisplayName("the sample prompt that was LLM-only now builds a join with HAVING")
    void buildsJoinWithHaving() {
        RuleBuildResult result = parser.parse("Which customers placed more than three orders", Dialect.MYSQL);

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
        RuleBuildResult result = parser.parse("which customers are in mumbai", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("the filter column is found in the data, not hardcoded to city")
    void filterColumnComesFromTheData() {
        RuleBuildResult result = parser.parse("customers in pune", Dialect.MYSQL);

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.trace().bindings())
                .anySatisfy(b -> assertThat(b.resolvedTo()).isEqualTo("customers.city"));
    }

    @Test
    @DisplayName("a value present in no column of the bound table declines")
    void ungroundedValueDeclines() {
        RuleBuildResult result = parser.parse("customers in atlantis", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).isNotBlank();
    }

    @Test
    @DisplayName("per <column> becomes a real GROUP BY instead of being dropped")
    void perColumnGroups() {
        RuleBuildResult result = parser.parse("how many orders are there per status", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("GROUP BY status").contains("COUNT(");
    }

    @Test
    @DisplayName("a breakdown with no total, average or count to break down declines")
    void groupingWithoutAggregateDeclines() {
        for (String question : List.of("total amount of payments per method",
                "total quantity per status of orders")) {
            RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
            assertThat(result.built()).as("%s -> %s", question, result.sql()).isFalse();
            assertThat(result.trace().declineReason()).as(question).contains("nothing to group");
        }
    }

    @Test
    @DisplayName("a counted breakdown still builds")
    void countedBreakdownStillBuilds() {
        RuleBuildResult result = parser.parse("how many customers are there per city", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("GROUP BY city").contains("COUNT(");
    }

    @Test
    @DisplayName("a temporal expression declines, but the word 'may' alone does not")
    void temporalDeclinesButModalMayDoesNot() {
        assertThat(parser.parse("orders from last month", Dialect.MYSQL).built()).isFalse();
        assertThat(parser.parse("show all customers", Dialect.MYSQL).built()).isTrue();
    }

    @Test
    @DisplayName("two tables with no declared relationship decline")
    void unrelatedPairDeclines() {
        RuleBuildResult result = parser.parse("customers and suppliers", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("relationship");
    }

    @Test
    @DisplayName("the built-in sample prompts are unchanged")
    void builtInSamplePromptsUnchanged() {
        assertThat(parser.parse("Show all customers", Dialect.MYSQL).sql()).isEqualTo("SELECT * FROM customers;");
        assertThat(parser.parse("Show only the name and email of customers", Dialect.MYSQL).sql())
                .isEqualTo("SELECT name, email FROM customers;");
        assertThat(parser.parse("How many orders are there", Dialect.MYSQL).sql())
                .isEqualTo("SELECT COUNT(*) FROM orders;");
    }

    @Test
    @DisplayName("a bare '<table> <column>' compound projects that column")
    void trailingCompoundProjectsTheColumn() {
        assertThat(parser.parse("customer name", Dialect.MYSQL).sql())
                .isEqualTo("SELECT name FROM customers;");
        assertThat(parser.parse("customer city", Dialect.MYSQL).sql())
                .isEqualTo("SELECT city FROM customers;");
        assertThat(parser.parse("show customer phone", Dialect.MYSQL).sql())
                .isEqualTo("SELECT phone FROM customers;");
    }

    @Test
    @DisplayName("a multi-word compound binds through underscore normalisation")
    void multiWordCompoundBinds() {
        activeSchema.publish(twoNameSchema());

        assertThat(parser.parse("customer first name", Dialect.MYSQL).sql())
                .isEqualTo("SELECT first_name FROM customers;");
    }

    @Test
    @DisplayName("a compound that resolves to two columns declines instead of picking one")
    void ambiguousCompoundDeclines() {
        activeSchema.publish(twoNameSchema());

        RuleBuildResult result = parser.parse("customer name", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).isNotBlank();
    }

    @Test
    @DisplayName("a prepositional tail is not a projection attempt")
    void prepositionalTailIsNotAProjection() {
        assertThat(parser.parse("customers in pune", Dialect.MYSQL).sql())
                .isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(parser.parse("show all customers", Dialect.MYSQL).sql())
                .isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("an aggregate over an unbindable column declines rather than guessing")
    void unbindableAggregateColumnDeclines() {
        assertThat(parser.parse("average of the flurb of customers", Dialect.MYSQL).built()).isFalse();
    }

    @Test
    @DisplayName("a comparison on a real column becomes a WHERE, not a HAVING")
    void plainComparisonBecomesWhere() {
        RuleBuildResult result = parser.parse("products with price above 100", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM products WHERE price > 100;");
    }

    @Test
    @DisplayName("two cities joined by \"and\" both survive, as an IN list")
    void twoValuesBecomeAnInList() {
        RuleBuildResult result = parser.parse("customers in mumbai and pune", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("WHERE city IN ('mumbai', 'pune')");
    }

    @Test
    @DisplayName("a single value still renders as equality, not a one-item IN")
    void oneValueStaysEquality() {
        RuleBuildResult result = parser.parse("customers in mumbai", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("WHERE city = 'mumbai'");
    }

    @Test
    @DisplayName("values belonging to different columns are declined rather than guessed")
    void valuesFromDifferentColumnsDecline() {
        when(metadata.getDistinctValues(eq("customers"), eq("name"))).thenReturn(List.of("ravi"));

        RuleBuildResult result = parser.parse("customers in mumbai and ravi", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("different columns");
    }

    @Test
    @DisplayName("a word after \"and\" that is not a value ends the list instead of declining")
    void trailingNonValueEndsTheList() {
        RuleBuildResult result = parser.parse("customers in mumbai and ordered by name", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("WHERE city = 'mumbai'");
    }

    @Test
    @DisplayName("\"with <column> <value>\" naming an unknown column declines instead of dropping the filter")
    void unknownWithColumnDeclines() {
        RuleBuildResult result = parser.parse("customers with gender female", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("gender");
    }

    @Test
    @DisplayName("\"with more than\" is comparison phrasing, not a filter on a column called more")
    void withMoreThanIsNotTreatedAsAColumn() {
        RuleBuildResult result = parser.parse("customers with more than three orders", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("HAVING");
    }

    @Test
    @DisplayName("a real \"with <column> <value>\" filter still binds")
    void withKnownColumnStillBinds() {
        RuleBuildResult result = parser.parse("customers with city mumbai", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("WHERE city = 'mumbai'");
    }

    @Test
    @DisplayName("\"removed\" is not the verb \"remove\" — the question still asks to show")
    void removedIsNotADeleteVerb() {
        RuleBuildResult result = parser.parse("show removed customers in mumbai", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).startsWith("SELECT").doesNotContain("DELETE");
    }

    @Test
    @DisplayName("\"deleted\" is not the verb \"delete\" either")
    void deletedIsNotADeleteVerb() {
        RuleBuildResult result = parser.parse("show deleted customers", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).startsWith("SELECT").doesNotContain("DELETE");
    }

    @Test
    @DisplayName("a question that opens with the verb still builds a DELETE")
    void leadingDeleteStillBuildsADelete() {
        RuleBuildResult result = parser.parse("delete customers in mumbai", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("DELETE FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("\"remove\" leading the question builds a DELETE too")
    void leadingRemoveBuildsADelete() {
        RuleBuildResult result = parser.parse("remove customers in mumbai", Dialect.MYSQL);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).startsWith("DELETE FROM customers");
    }

    @Test
    @DisplayName("the verb standing mid-question is ambiguous, so the engine declines")
    void midQuestionDeleteDeclines() {
        RuleBuildResult result = parser.parse("customers in mumbai i want to delete", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("delete");
    }

    @Test
    @DisplayName("a column dropped from the database inside an aggregate declines instead of building SQL")
    void aggregateColumnDroppedFromDatabaseDeclines() {
        when(metadata.getColumnNames(eq("products")))
                .thenReturn(List.of("id", "name", "category_id", "supplier_id"));

        RuleBuildResult result = parser.parse("average price of products", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("not in the database right now");
    }

    @Test
    @DisplayName("a HAVING count-target column dropped from the database declines too")
    void havingCountTargetColumnDroppedFromDatabaseDeclines() {
        when(metadata.getColumnNames(eq("orders")))
                .thenReturn(List.of("customer_id", "employee_id", "order_date", "status"));

        RuleBuildResult result = parser.parse("Which customers placed more than three orders", Dialect.MYSQL);

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("not in the database right now");
    }
}
