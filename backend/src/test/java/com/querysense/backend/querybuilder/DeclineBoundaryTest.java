package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
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
        SchemaMetadata metadata = mock(SchemaMetadata.class);
        when(metadata.dialect()).thenReturn(Dialect.MYSQL);
        SchemaMetadata mongoMetadata = mock(SchemaMetadata.class);
        when(mongoMetadata.dialect()).thenReturn(Dialect.MONGODB);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getColumnNames(anyString())).thenReturn(List.of());

        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        builder = new RuleBasedQueryBuilder(
                TestParsers.parser(activeSchema, metadata, mongoMetadata));
    }

    @ParameterizedTest
    @DisplayName("these must always decline to the language model")
    @ValueSource(strings = {
            "orders from last month",
            "customers who joined in January",
            "show me everything interesting",
            "list the top 5 punters by total spend",
            "list the top 5 buyers by total spend",
            "customers and suppliers",
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
    @DisplayName("MongoDB declines the same questions MySQL does")
    @ValueSource(strings = {"orders from last month", "departments and suppliers", "delete all customers"})
    void mongoDeclinesWhatMysqlDeclines(String input) {
        assertThat(builder.build(input, "MongoDB").built()).as("input: %s", input).isFalse();
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
