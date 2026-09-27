package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleTraceWordingTest {

    private static final List<String> BANNED = List.of(
            "accesstype", "explain", "possible_keys", "filesort", "eq_ref",
            "anant", "vedant", "eeshaan", "nullpointer", "optional.empty");

    private static final List<String> MONGO_BANNED = List.of(
            "$match", "$lookup", "$unwind", "$group", "aggregate(");

    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        SchemaMetadata metadata = mock(SchemaMetadata.class);
        when(metadata.dialect()).thenReturn(Dialect.MYSQL);
        SchemaMetadata mongoMetadata = mock(SchemaMetadata.class);
        when(mongoMetadata.dialect()).thenReturn(Dialect.MONGODB);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getDistinctValues(eq("customers"), eq("city")))
                .thenReturn(List.of("Mumbai", "Pune"));
        when(metadata.getColumnNames(anyString())).thenReturn(List.of());

        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        builder = new RuleBasedQueryBuilder(
                TestParsers.parser(activeSchema, metadata, mongoMetadata));
    }

    @Test
    @DisplayName("no decline reason leaks database vocabulary or a teammate name")
    void declineReasonsAreJargonFree() {
        List<String> inputs = List.of(
                "orders from last month",
                "customers and suppliers",
                "show me everything interesting",
                "how many customers are there per flurb",
                "delete all customers",
                "average of the flurb of customers");

        for (String input : inputs) {
            String reason = builder.build(input, "MySQL").trace().declineReason();
            assertThat(reason).as("input: %s", input).isNotBlank();

            String lower = reason.toLowerCase(Locale.ROOT);
            for (String banned : BANNED) {
                assertThat(lower).as("input: %s", input).doesNotContain(banned);
            }
        }
    }

    @Test
    @DisplayName("no MongoDB decline reason names a pipeline stage")
    void mongoDeclineReasonsAreJargonFree() {
        List<String> inputs = List.of(
                "sum of quantity of orders with quantity 2",
                "how many customers per city sorted by name",
                "orders with status shipped",
                "total amount of payments per method",
                "show all order items",
                "customers and suppliers",
                "delete all customers");

        for (String input : inputs) {
            var result = builder.build(input, "MongoDB");
            assertThat(result.built()).as("input: %s", input).isFalse();
            String lower = result.trace().declineReason().toLowerCase(Locale.ROOT);
            for (String banned : MONGO_BANNED) {
                assertThat(lower).as("input: %s", input).doesNotContain(banned);
            }
            for (String banned : BANNED) {
                assertThat(lower).as("input: %s", input).doesNotContain(banned);
            }
        }
    }

    @Test
    @DisplayName("a built trace says the join came from the schema, in those words")
    void joinOriginIsStatedPlainly() {
        var trace = builder.build("Which customers placed more than three orders", "MySQL").trace();

        assertThat(trace.joinEdge()).isNotNull();
        assertThat(trace.joinEdge().origin()).isEqualTo("declared foreign key");
        assertThat(trace.checksPassed())
                .anySatisfy(c -> assertThat(c.toLowerCase(Locale.ROOT)).contains("foreign key"));
    }

    @Test
    @DisplayName("no check or shape label leaks database vocabulary either")
    void checksAndShapesAreJargonFree() {
        var trace = builder.build("Which customers placed more than three orders", "MySQL").trace();

        String all = String.join(" ", trace.checksPassed()).toLowerCase(Locale.ROOT);
        for (String banned : BANNED) {
            assertThat(all).doesNotContain(banned);
        }
    }
}
