package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Matches words in a question to tables and columns of the active schema. */
@Component
public class SchemaBinder {

    private final ActiveSchemaProvider activeSchema;
    private final SchemaLexicon lexicon;

    public SchemaBinder(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
        this.lexicon = new SchemaLexicon(activeSchema, Synonyms.builtIn());
    }

    public List<KnownSchema.Table> bindTables(String normalizedText, Dialect dialect) {
        return lexicon.tables(normalizedText, dialect).hits().stream().map(SchemaLexicon.TableHit::table).toList();
    }

    public SchemaLexicon.TableMatches tableMatches(String normalizedText, Dialect dialect) {
        return lexicon.tables(normalizedText, dialect);
    }

    public boolean isTableWord(String word, Dialect dialect) {
        return lexicon.isTableWord(word, dialect);
    }

    public Optional<String> bindColumn(KnownSchema.Table table, String phrase) {
        return lexicon.exactOrAlias(table, phrase, aliasesFor(table)).map(SchemaLexicon.Binding::item);
    }

    public SchemaLexicon.ColumnMatch columnMatch(KnownSchema.Table table, String phrase) {
        return lexicon.column(table, phrase, aliasesFor(table));
    }

    public int longestColumnPhrase(List<KnownSchema.Table> tables) {
        int longest = 1;
        for (KnownSchema.Table table : tables) {
            SchemaAliases.TableAliases aliases = aliasesFor(table);
            for (String column : table.columns()) {
                longest = Math.max(longest, NameWords.words(column).size());
                for (String alias : aliases.column(column)) {
                    longest = Math.max(longest, alias.trim().split("\\s+").length);
                }
            }
        }
        return longest;
    }

    private Optional<SchemaSnapshot> snapshotOf(KnownSchema.Table table) {
        for (Dialect dialect : Dialect.values()) {
            SchemaSnapshot snapshot = activeSchema.active(dialect);
            if (snapshot.tables().contains(table)) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    private SchemaAliases.TableAliases aliasesFor(KnownSchema.Table table) {
        return snapshotOf(table).map(s -> s.aliasesFor(table.name())).orElse(SchemaAliases.TableAliases.NONE);
    }

    public String fieldType(KnownSchema.Table table, String column) {
        return snapshotOf(table).map(s -> s.fieldTypesFor(table.name()).get(column)).orElse(null);
    }

    public String primaryKey(KnownSchema.Table table) {
        return snapshotOf(table).map(s -> s.primaryKeyFor(table)).orElse(table.columns().get(0));
    }

    public Set<String> foreignKeyColumns(KnownSchema.Table table) {
        return snapshotOf(table).map(s -> s.foreignKeys().stream()
                        .filter(fk -> fk.fromTable().equalsIgnoreCase(table.name()))
                        .map(KnownSchema.ForeignKey::fromColumn)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public static boolean isTextType(String type) {
        return type != null && Set.of("VARCHAR", "CHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
                "NVARCHAR", "NCHAR", "STRING").contains(type.toUpperCase(Locale.ROOT));
    }

    public static boolean isDateType(String type) {
        return type != null && Set.of("DATE", "DATETIME", "TIMESTAMP").contains(type.toUpperCase(Locale.ROOT));
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", " ").trim();
    }

    public List<KnownSchema.Table> tables(Dialect dialect) {
        return activeSchema.tables(dialect);
    }

    public Optional<KnownSchema.Table> tableNamed(String name, Dialect dialect) {
        return activeSchema.findTable(dialect, name);
    }
}
