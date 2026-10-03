package com.querysense.backend.querybuilder;

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

class SchemaLexiconTest {

    private static final KnownSchema.Table CUSTOMERS = new KnownSchema.Table("customers", List.of("id", "name", "city", "email"));
    private static final KnownSchema.Table SUPPLIERS = new KnownSchema.Table("suppliers", List.of("id", "phone", "mobile"));
    private static final KnownSchema.Table ACCOUNTS = new KnownSchema.Table("accounts", List.of("id", "owner"));
    private static final KnownSchema.Table PEOPLE = new KnownSchema.Table("person", List.of("id", "addresses"));

    private ActiveSchemaProvider schemas;
    private SchemaLexicon lexicon;

    private void publish(List<KnownSchema.Table> tables, Map<String, SchemaAliases.TableAliases> aliases) {
        schemas.publish(new SchemaSnapshot("upload-l", "l", "querysense_uploads", tables, List.of(), Map.of(),
                Instant.now(), Dialect.MYSQL, List.of(), Map.of(), Map.of(), aliases));
    }

    @BeforeEach
    void setUp() {
        schemas = new ActiveSchemaProvider();
        lexicon = new SchemaLexicon(schemas, Synonyms.builtIn());
        publish(List.of(CUSTOMERS, SUPPLIERS, PEOPLE), Map.of());
    }

    private SchemaLexicon.Binding onlyHit(String text) {
        SchemaLexicon.TableMatches matches = lexicon.tables(text, Dialect.MYSQL);
        assertThat(matches.ambiguity()).isEmpty();
        assertThat(matches.hits()).hasSize(1);
        return matches.hits().get(0).binding();
    }

    @Test
    @DisplayName("each match kind reaches its table")
    void tableKinds() {
        assertThat(onlyHit("show customers")).isEqualTo(new SchemaLexicon.Binding("customers", "customers", SchemaLexicon.MatchKind.EXACT));
        assertThat(onlyHit("each customer").kind()).isEqualTo(SchemaLexicon.MatchKind.INFLECTION);
        assertThat(onlyHit("all people").item()).isEqualTo("person");
        assertThat(onlyHit("clients in pune")).isEqualTo(new SchemaLexicon.Binding("customers", "clients", SchemaLexicon.MatchKind.SYNONYM));
        assertThat(onlyHit("custmers in pune")).isEqualTo(new SchemaLexicon.Binding("customers", "custmers", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("aliasBeatsSynonym: a declared alias wins over a built-in synonym of another table")
    void aliasBeatsSynonym() {
        publish(List.of(CUSTOMERS, ACCOUNTS), Map.of("accounts", new SchemaAliases.TableAliases(List.of("clients"), Map.of())));

        assertThat(onlyHit("clients in pune")).isEqualTo(new SchemaLexicon.Binding("accounts", "clients", SchemaLexicon.MatchKind.SCHEMA_ALIAS));
    }

    @Test
    @DisplayName("a typo is only tried when no table matched otherwise, and never on short words")
    void typoLimits() {
        assertThat(lexicon.tables("customers near the bordr", Dialect.MYSQL).hits()).extracting(h -> h.table().name())
                .containsExactly("customers");
        assertThat(lexicon.tables("custs", Dialect.MYSQL).hits()).isEmpty();
    }

    @Test
    @DisplayName("columns: exact, inflection, alias, synonym, typo, and ambiguity")
    void columns() {
        SchemaAliases.TableAliases aliases = new SchemaAliases.TableAliases(List.of(), Map.of("city", List.of("locality")));

        assertThat(bound(CUSTOMERS, "email", SchemaAliases.TableAliases.NONE).kind()).isEqualTo(SchemaLexicon.MatchKind.EXACT);
        assertThat(bound(CUSTOMERS, "emails", SchemaAliases.TableAliases.NONE).kind()).isEqualTo(SchemaLexicon.MatchKind.INFLECTION);
        assertThat(bound(CUSTOMERS, "locality", aliases)).isEqualTo(new SchemaLexicon.Binding("city", "locality", SchemaLexicon.MatchKind.SCHEMA_ALIAS));
        assertThat(bound(CUSTOMERS, "town", SchemaAliases.TableAliases.NONE)).isEqualTo(new SchemaLexicon.Binding("city", "town", SchemaLexicon.MatchKind.SYNONYM));
        assertThat(bound(CUSTOMERS, "emial", SchemaAliases.TableAliases.NONE)).isEqualTo(new SchemaLexicon.Binding("email", "emial", SchemaLexicon.MatchKind.TYPO));
        assertThat(bound(PEOPLE, "address", SchemaAliases.TableAliases.NONE).item()).isEqualTo("addresses");

        SchemaLexicon.ColumnMatch telephone = lexicon.column(SUPPLIERS, "telephone", SchemaAliases.TableAliases.NONE);
        assertThat(telephone).isInstanceOfSatisfying(SchemaLexicon.Ambiguous.class,
                a -> assertThat(a.ambiguity().candidates()).containsExactlyInAnyOrder("phone", "mobile"));
        assertThat(lexicon.column(CUSTOMERS, "flurb", SchemaAliases.TableAliases.NONE)).isInstanceOf(SchemaLexicon.Unbound.class);
    }

    @Test
    @DisplayName("exactOrAlias ignores synonyms and typos")
    void exactOrAlias() {
        assertThat(lexicon.exactOrAlias(CUSTOMERS, "town", SchemaAliases.TableAliases.NONE)).isEmpty();
        assertThat(lexicon.exactOrAlias(CUSTOMERS, "city", SchemaAliases.TableAliases.NONE)).isPresent();
    }

    private SchemaLexicon.Binding bound(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        SchemaLexicon.ColumnMatch match = lexicon.column(table, phrase, aliases);
        assertThat(match).isInstanceOf(SchemaLexicon.Bound.class);
        return ((SchemaLexicon.Bound) match).binding();
    }
}
