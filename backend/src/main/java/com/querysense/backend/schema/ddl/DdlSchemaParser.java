package com.querysense.backend.schema.ddl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.upload.SchemaUploadParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses CREATE TABLE statements from uploaded DDL. */
@Component
public class DdlSchemaParser implements SchemaUploadParser {

    private static final Logger log = LoggerFactory.getLogger(DdlSchemaParser.class);
    private static final int WARNING_SNIPPET_LENGTH = 80;

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public ParsedSchema parse(String ddl) {
        List<ParsedTable> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, SchemaAliases.TableAliases> aliases = new LinkedHashMap<>();

        for (String statement : SqlStatementSplitter.split(ddl)) {
            if (!startsCreateTable(statement)) {
                continue;
            }
            List<String> statementWarnings = new ArrayList<>();
            try {
                Statement parsed = CCJSqlParserUtil.parse(PostgresDdl.clean(statement, statementWarnings));
                if (parsed instanceof CreateTable createTable) {
                    ParsedTable table = toParsedTable(createTable, statementWarnings);
                    tables.add(table);
                    warnings.addAll(statementWarnings);
                    aliasesOf(createTable).ifPresent(a -> aliases.put(table.name(), a));
                }
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\\R", 2)[0];
                warnings.add(skippedTableWarning(PostgresDdl.tableName(statement), reason, snippet(statement)));
                log.warn("Skipping unparseable DDL statement: {}", e.getMessage());
            }
        }

        return new ParsedSchema(widenKeyedText(tables), List.copyOf(warnings), List.of(), Map.copyOf(aliases));
    }

    private static final String SKIPPED_PREFIX = "Skipped table '";

    private static String skippedTableWarning(String table, String reason, String snippet) {
        return SKIPPED_PREFIX + table + "': it could not be read (" + reason + "). Statement: " + snippet;
    }

    public static Optional<String> skipReason(List<String> warnings, String table) {
        String marker = SKIPPED_PREFIX + table + "'";
        return warnings.stream()
                .filter(w -> w.regionMatches(true, 0, marker, 0, marker.length()))
                .findFirst()
                .map(w -> w.substring(w.indexOf("(") + 1, w.indexOf("). Statement:")));
    }

    private List<ParsedTable> widenKeyedText(List<ParsedTable> tables) {
        Set<String> keyed = new HashSet<>();
        for (ParsedTable table : tables) {
            for (ParsedColumn c : table.columns()) {
                if (c.primaryKey() || c.unique()) {
                    keyed.add(key(table.name(), c.name()));
                }
            }
            for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
                keyed.add(key(fk.fromTable(), fk.fromColumn()));
                keyed.add(key(fk.toTable(), fk.toColumn()));
            }
        }
        List<ParsedTable> out = new ArrayList<>();
        for (ParsedTable table : tables) {
            List<ParsedColumn> columns = table.columns().stream()
                    .map(c -> keyed.contains(key(table.name(), c.name()))
                            && PostgresDdl.TEXT_TYPES.contains(c.normalisedType())
                            ? new ParsedColumn(c.name(), "VARCHAR", List.of("255"), c.notNull(), c.autoIncrement(),
                                    c.unique(), c.primaryKey())
                            : c)
                    .toList();
            out.add(new ParsedTable(table.name(), columns, table.foreignKeys(), table.embeddedArrays(), table.validator()));
        }
        return List.copyOf(out);
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    private Optional<SchemaAliases.TableAliases> aliasesOf(CreateTable createTable) {
        List<String> table = SchemaAliases.fromComment(commentIn(createTable.getTableOptionsStrings()));
        Map<String, List<String>> columns = new LinkedHashMap<>();
        if (createTable.getColumnDefinitions() != null) {
            for (ColumnDefinition definition : createTable.getColumnDefinitions()) {
                List<String> found = SchemaAliases.fromComment(commentIn(definition.getColumnSpecs()));
                if (!found.isEmpty()) {
                    columns.put(unquote(definition.getColumnName()), found);
                }
            }
        }
        return table.isEmpty() && columns.isEmpty()
                ? Optional.empty()
                : Optional.of(new SchemaAliases.TableAliases(table, Map.copyOf(columns)));
    }

    private static String commentIn(List<String> tokens) {
        if (tokens == null) {
            return null;
        }
        for (int i = 0; i < tokens.size() - 1; i++) {
            if ("COMMENT".equalsIgnoreCase(tokens.get(i))) {
                String next = tokens.get(i + 1);
                return "=".equals(next) && i + 2 < tokens.size() ? tokens.get(i + 2) : next;
            }
        }
        return null;
    }

    private boolean startsCreateTable(String statement) {
        String head = statement.stripLeading().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return head.startsWith("CREATE TABLE");
    }

    private ParsedTable toParsedTable(CreateTable createTable, List<String> warnings) {
        String tableName = unquote(createTable.getTable().getName());
        List<ParsedColumn> columns = new ArrayList<>();
        List<KnownSchema.ForeignKey> foreignKeys = new ArrayList<>();

        List<ColumnDefinition> definitions =
                createTable.getColumnDefinitions() == null ? List.of() : createTable.getColumnDefinitions();

        for (ColumnDefinition definition : definitions) {
            String columnName = unquote(definition.getColumnName());
            List<String> specs = definition.getColumnSpecs() == null ? List.of() : definition.getColumnSpecs();
            String specText = String.join(" ", specs).toUpperCase(Locale.ROOT);

            String declaredType = bareType(definition.getColDataType().getDataType());
            PostgresDdl.MappedType type = PostgresDdl.mapType(declaredType, typeArgsOf(definition));
            if (type.storedAsText()) {
                warnings.add("Stored " + tableName + "." + columnName + " (PostgreSQL type "
                        + declaredType.toUpperCase(Locale.ROOT) + ") as TEXT in the sample database.");
            }
            columns.add(new ParsedColumn(
                    columnName,
                    type.type(),
                    type.args(),
                    specText.contains("NOT NULL"),
                    type.serial() || specText.contains("AUTO_INCREMENT"),
                    specText.contains("UNIQUE"),
                    specText.contains("PRIMARY KEY")));

            inlineForeignKey(tableName, columnName, specs).ifPresent(foreignKeys::add);
        }

        markPrimaryKeyColumns(createTable, columns);
        foreignKeys.addAll(tableLevelForeignKeys(createTable, tableName));

        return new ParsedTable(tableName, List.copyOf(columns), List.copyOf(foreignKeys));
    }

    private static final Pattern TYPE_WITH_ARGS =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z_ ]*?)\\s*\\((.*)\\)\\s*$", Pattern.DOTALL);

    private List<String> typeArgsOf(ColumnDefinition definition) {
        List<String> declared = definition.getColDataType().getArgumentsStringList();
        if (declared != null && !declared.isEmpty()) {
            return List.copyOf(declared);
        }
        return typeArgs(definition.getColDataType().getDataType());
    }

    private String bareType(String rawType) {
        if (rawType == null) {
            return "";
        }
        Matcher m = TYPE_WITH_ARGS.matcher(rawType);
        return (m.matches() ? m.group(1) : rawType).trim();
    }

    private List<String> typeArgs(String rawType) {
        if (rawType == null) {
            return List.of();
        }
        Matcher m = TYPE_WITH_ARGS.matcher(rawType);
        return m.matches() ? splitArgs(m.group(2)) : List.of();
    }

    private List<String> splitArgs(String args) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                addTrimmed(out, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addTrimmed(out, current);
        return List.copyOf(out);
    }

    private void addTrimmed(List<String> target, StringBuilder value) {
        String trimmed = value.toString().trim();
        if (!trimmed.isEmpty()) {
            target.add(trimmed);
        }
    }

    private void markPrimaryKeyColumns(CreateTable createTable, List<ParsedColumn> columns) {
        if (createTable.getIndexes() == null) {
            return;
        }
        for (Index index : createTable.getIndexes()) {
            if (index.getType() == null || !index.getType().toUpperCase(Locale.ROOT).contains("PRIMARY")) {
                continue;
            }
            for (String raw : index.getColumnsNames()) {
                String name = unquote(raw);
                for (int i = 0; i < columns.size(); i++) {
                    ParsedColumn c = columns.get(i);
                    if (c.name().equalsIgnoreCase(name)) {
                        columns.set(i, new ParsedColumn(c.name(), c.type(), c.typeArgs(),
                                true, c.autoIncrement(), c.unique(), true));
                    }
                }
            }
        }
    }

    private List<KnownSchema.ForeignKey> tableLevelForeignKeys(CreateTable createTable, String tableName) {
        List<KnownSchema.ForeignKey> keys = new ArrayList<>();
        if (createTable.getIndexes() == null) {
            return keys;
        }
        for (Index index : createTable.getIndexes()) {
            if (!(index instanceof ForeignKeyIndex fk)) {
                continue;
            }
            List<String> from = fk.getColumnsNames();
            List<String> to = fk.getReferencedColumnNames();
            if (from == null || to == null) {
                continue;
            }
            String toTable = unquote(fk.getTable().getName());
            for (int i = 0; i < from.size() && i < to.size(); i++) {
                keys.add(new KnownSchema.ForeignKey(
                        tableName, unquote(from.get(i)), toTable, unquote(to.get(i))));
            }
        }
        return keys;
    }

    private Optional<KnownSchema.ForeignKey> inlineForeignKey(
            String tableName, String columnName, List<String> specs) {
        for (int i = 0; i < specs.size(); i++) {
            if (!"REFERENCES".equalsIgnoreCase(specs.get(i)) || i + 1 >= specs.size()) {
                continue;
            }
            String target = specs.get(i + 1);
            String targetTable;
            String targetColumn = "id";

            int open = target.indexOf('(');
            if (open > -1) {
                targetTable = unquote(target.substring(0, open));
                targetColumn = unquote(target.substring(open + 1).replace(")", ""));
            } else {
                targetTable = unquote(target);
                if (i + 2 < specs.size() && specs.get(i + 2).startsWith("(")) {
                    targetColumn = unquote(specs.get(i + 2).replace("(", "").replace(")", ""));
                }
            }
            return Optional.of(new KnownSchema.ForeignKey(tableName, columnName, targetTable, targetColumn));
        }
        return Optional.empty();
    }

    private String unquote(String identifier) {
        if (identifier == null) {
            return "";
        }
        String cleaned = identifier.replace("`", "").replace("\"", "").trim();
        int dot = cleaned.lastIndexOf('.');
        return dot > -1 ? cleaned.substring(dot + 1) : cleaned;
    }

    private String snippet(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() <= WARNING_SNIPPET_LENGTH ? flat : flat.substring(0, WARNING_SNIPPET_LENGTH) + "...";
    }
}
