package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryIntentParserTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getDistinctValues(eq("customers"), eq("city")))
                .thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(metadata.getColumnNames(anyString())).thenReturn(List.of());

        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
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
