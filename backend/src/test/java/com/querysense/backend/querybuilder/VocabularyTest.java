package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
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

/** The rule engine on a schema that declares no aliases: synonyms, typos, values and numbers. */
class VocabularyTest {

    private static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("customers", List.of("id", "name", "city", "email")),
            new KnownSchema.Table("orders", List.of("id", "customer_id", "amount", "status")),
            new KnownSchema.Table("suppliers", List.of("id", "name", "phone", "mobile")),
            new KnownSchema.Table("stores", List.of("id", "city", "region")));

    private ActiveSchemaProvider schemas;
    private QueryIntentParser parser;

    static SchemaSnapshot upload(Dialect dialect, Map<String, SchemaAliases.TableAliases> aliases) {
        return new SchemaSnapshot("upload-v", "shop", "querysense_uploads", TABLES,
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")),
                Map.of(), Instant.now(), dialect, List.of(), Map.of(), Map.of(), aliases);
    }

    static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "New Delhi"));
        when(m.getDistinctValues(eq("stores"), eq("city"))).thenReturn(List.of("Central", "Mumbai"));
        when(m.getDistinctValues(eq("stores"), eq("region"))).thenReturn(List.of("Central", "West"));
        return m;
    }

    @BeforeEach
    void setUp() {
        schemas = new ActiveSchemaProvider();
        schemas.publish(upload(Dialect.MYSQL, Map.of()));
        schemas.publish(upload(Dialect.MONGODB, Map.of()));
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    private RuleBuildResult sql(String question) {
        return parser.parse(question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("a built-in synonym reaches the table, and is recorded as an interpretation")
    void synonymTable() {
        RuleBuildResult result = sql("clients in mumbai");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("clients", "customers", SchemaLexicon.MatchKind.SYNONYM));
        assertThat(result.interpreted()).isTrue();
    }

    @Test
    @DisplayName("a one-letter typo in the table name is corrected and recorded")
    void typoTable() {
        RuleBuildResult result = sql("custmers in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("custmers", "customers", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("a typo in a column is corrected and recorded")
    void typoColumn() {
        RuleBuildResult result = sql("show emial of customers");

        assertThat(result.sql()).isEqualTo("SELECT email FROM customers;");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("emial", "customers.email", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("a synonym that fits two columns declines, naming both")
    void ambiguousSynonymDeclines() {
        RuleBuildResult result = sql("telephone of suppliers");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason())
                .isEqualTo("\"telephone\" could mean suppliers.phone or suppliers.mobile, so this engine won't pick one.");
    }

    @Test
    @DisplayName("a declared alias is recorded, but doesn't count as an interpretation that lowers confidence")
    void aliasIsNotInterpreted() {
        schemas.publish(upload(Dialect.MYSQL, Map.of("customers",
                new SchemaAliases.TableAliases(List.of("regulars"), Map.of()))));

        RuleBuildResult result = sql("regulars in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("regulars", "customers", SchemaLexicon.MatchKind.SCHEMA_ALIAS));
        assertThat(result.interpreted()).isFalse();
    }

    @Test
    @DisplayName("nameAndSynonymOfOneTable: the real name and a synonym of it are one table")
    void nameAndSynonymOfOneTable() {
        RuleBuildResult result = sql("customers and clients in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("an exact question has no interpretations")
    void exactHasNone() {
        RuleBuildResult result = sql("customers in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.interpretations()).isEmpty();
    }
}
