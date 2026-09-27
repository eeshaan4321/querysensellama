package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnboundNounTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-b1", "two.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers", List.of("id", "name", "city")),
                        new KnownSchema.Table("orders", List.of("id", "customer_id", "status"))),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")),
                Map.of(), Instant.now()));
        SchemaMetadata mysql = mock(SchemaMetadata.class);
        when(mysql.dialect()).thenReturn(Dialect.MYSQL);
        when(mysql.getColumnNames(anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        SchemaMetadata mongo = mock(SchemaMetadata.class);
        when(mongo.dialect()).thenReturn(Dialect.MONGODB);
        parser = TestParsers.parser(schemas, mysql, mongo);
    }

    @ParameterizedTest
    @DisplayName("a second noun the schema cannot bind declines, naming the word")
    @ValueSource(strings = {"customers and suppliers", "customers and widgets", "show customers plus invoices"})
    void unboundSecondNounDeclines(String input) {
        RuleBuildResult result = parser.parse(input, Dialect.MYSQL);

        assertThat(result.built()).as("input: %s built %s", input, result.sql()).isFalse();
        String word = input.substring(input.lastIndexOf(' ') + 1);
        assertThat(result.trace().declineReason()).contains("\"" + word + "\"");
    }

    @Test
    @DisplayName("two nouns that both bind still build their join")
    void boundPairStillJoins() {
        RuleBuildResult result = parser.parse("customers and orders", Dialect.MYSQL);
        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("JOIN orders ON customers.id = orders.customer_id");
    }

    @Test
    @DisplayName("'and' followed by a word that is not a noun is left alone")
    void nonNounAfterAndIsIgnored() {
        assertThat(parser.parse("customers and their orders", Dialect.MYSQL).built()).isTrue();
    }
}
