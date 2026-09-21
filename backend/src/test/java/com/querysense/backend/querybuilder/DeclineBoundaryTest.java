package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeclineBoundaryTest {

    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getColumnNames(anyString())).thenReturn(List.of());

        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        builder = new RuleBasedQueryBuilder(
                new QueryIntentParser(
                        new SchemaBinder(activeSchema),
                        new JoinPathResolver(activeSchema),
                        metadata,
                        new SqlEmitter()));
    }

    @ParameterizedTest
    @DisplayName("these must always decline to the language model")
    @ValueSource(strings = {
            "orders from last month",
            "customers who joined in January",
            "show me everything interesting",
            "list the top 5 buyers by total spend",
            "customers and suppliers",
            "orders, customers and products together",
            "delete all customers",
            "average of the flurb of customers",
            "how many customers are there per flurb",
            "show only the flurb of customers",
            "customers sorted by flurb",
    })
    void mustDecline(String input) {
        assertThat(builder.build(input, "MySQL").built())
                .as("input: %s", input)
                .isFalse();
    }

    @ParameterizedTest
    @DisplayName("MongoDB always declines, however simple the question is")
    @ValueSource(strings = {"Show all customers", "how many orders are there"})
    void mongoAlwaysDeclines(String input) {
        assertThat(builder.build(input, "MongoDB").built()).isFalse();
    }

    @ParameterizedTest
    @DisplayName("every decline carries a reason a user could act on")
    @ValueSource(strings = {
            "orders from last month",
            "customers and suppliers",
            "show me everything interesting",
            "delete all customers",
    })
    void everyDeclineExplainsItself(String input) {
        assertThat(builder.build(input, "MySQL").trace().declineReason())
                .as("input: %s", input)
                .isNotBlank();
    }
}
