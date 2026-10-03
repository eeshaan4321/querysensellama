package com.querysense.backend.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaAliasesTest {

    private static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("customers", List.of("id", "name", "city")),
            new KnownSchema.Table("accounts", List.of("id", "owner")));

    @Test
    @DisplayName("aka: in a comment declares aliases; text before it is ignored")
    void fromComment() {
        assertThat(SchemaAliases.fromComment("'Registered buyers. aka: clients, patrons'"))
                .containsExactly("clients", "patrons");
        assertThat(SchemaAliases.fromComment("\"aka: mail\"")).containsExactly("mail");
        assertThat(SchemaAliases.fromComment("aka: order lines; internal use")).containsExactly("order lines");
        assertThat(SchemaAliases.fromComment("Where they live")).isEmpty();
        assertThat(SchemaAliases.fromComment(null)).isEmpty();
    }

    @Test
    @DisplayName("aliasesAreLowercased: capitals and underscores are normalised like names")
    void aliasesAreLowercased() {
        assertThat(SchemaAliases.fromComment("aka: Clients, BUYERS, Order_Lines"))
                .containsExactly("clients", "buyers", "order lines");
    }

    @Test
    @DisplayName("clean keeps good aliases and drops collisions, each with a warning")
    void clean() {
        Map<String, SchemaAliases.TableAliases> declared = Map.of(
                "customers", new SchemaAliases.TableAliases(List.of("clients", "accounts", "members"),
                        Map.of("city", List.of("town", "name"), "name", List.of("label"))),
                "accounts", new SchemaAliases.TableAliases(List.of("members"), Map.of()),
                "ghosts", new SchemaAliases.TableAliases(List.of("spirits"), Map.of()));

        SchemaAliases.Cleaned cleaned = SchemaAliases.clean(TABLES, declared);

        assertThat(cleaned.aliases().get("customers").table()).containsExactly("clients");
        assertThat(cleaned.aliases().get("customers").column("city")).containsExactly("town");
        assertThat(cleaned.aliases().get("customers").column("name")).containsExactly("label");
        assertThat(cleaned.aliases()).doesNotContainKey("accounts");
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("\"accounts\"").contains("table"));
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("\"members\"").contains("more than one"));
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("\"name\"").contains("customers.city"));
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("ghosts"));
    }

    @Test
    @DisplayName("the built-in schemas declare customers aka buyers; an unknown table has none")
    void builtIns() {
        assertThat(KnownSchema.builtIn().aliasesFor("customers").table()).containsExactly("buyers");
        assertThat(KnownMongoSchema.builtIn().aliasesFor("customers").table()).containsExactly("buyers");
        assertThat(KnownSchema.builtIn().aliasesFor("orders")).isEqualTo(SchemaAliases.TableAliases.NONE);
    }

    @Test
    @DisplayName("describe writes the aliases a prompt shows, or the bare name")
    void describe() {
        assertThat(SchemaAliases.describe("customers", List.of("clients", "buyers")))
                .isEqualTo("customers (also called: clients, buyers)");
        assertThat(SchemaAliases.describe("orders", List.of())).isEqualTo("orders");
    }

    @Test
    @DisplayName("merge unions two declarations without repeats")
    void merge() {
        SchemaAliases.TableAliases merged = SchemaAliases.merge(
                new SchemaAliases.TableAliases(List.of("clients"), Map.of("city", List.of("town"))),
                new SchemaAliases.TableAliases(List.of("clients", "patrons"), Map.of("city", List.of("locality"))));

        assertThat(merged.table()).containsExactly("clients", "patrons");
        assertThat(merged.column("city")).containsExactly("town", "locality");
    }
}
