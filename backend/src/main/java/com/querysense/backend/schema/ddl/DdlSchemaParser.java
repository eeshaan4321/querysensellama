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

        List<SampleInsert> samples = new ArrayList<>();
        List<String> alters = new ArrayList<>();
        for (String statement : SqlStatementSplitter.split(ddl)) {
            if (startsAlterTable(statement)) {
                alters.add(statement);
                continue;
            }
            if (!startsCreateTable(statement)) {
                InsertRows.read(statement).ifPresent(sample -> {
                    samples.add(sample);
                    if (sample.skippedRows() > 0) {
                        warnings.add("Skipped " + sample.skippedRows() + " sample row(s) for '" + sample.table()
                                + "': they use expressions rather than plain values.");
                    }
                });
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

        alters.forEach(statement -> applyAlter(statement, tables, warnings));

        return new ParsedSchema(widenKeyedText(tables), List.copyOf(warnings), List.of(), Map.copyOf(aliases),
                List.copyOf(samples));
    }

    private static final Pattern ALTER_HEAD = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?([`\"\\w.]+)\\s+(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String CONSTRAINT_NAME = "(?:CONSTRAINT\\s+[`\"\\w]+\\s+)?";
    private static final Pattern ADD_FOREIGN_KEY = Pattern.compile(
            "^ADD\\s+" + CONSTRAINT_NAME + "FOREIGN\\s+KEY\\s*(?:[`\"\\w]+\\s*)?\\(([^)]*)\\)\\s*"
                    + "REFERENCES\\s+([`\"\\w.]+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_PRIMARY_KEY = Pattern.compile(
            "^ADD\\s+" + CONSTRAINT_NAME + "PRIMARY\\s+KEY\\s*(?:USING\\s+\\w+\\s*)?\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_UNIQUE = Pattern.compile(
            "^ADD\\s+" + CONSTRAINT_NAME + "UNIQUE\\s*(?:KEY\\s+|INDEX\\s+)?(?:[`\"\\w]+\\s*)?\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NO_EFFECT_ON_SHAPE = Pattern.compile(
            "^(?:OWNER\\s+TO\\b|(?:DISABLE|ENABLE)\\s+KEYS\\b|AUTO_INCREMENT\\s*=|REPLICA\\s+IDENTITY\\b"
                    + "|ALTER\\s+COLUMN\\s+\\S+\\s+SET\\s+DEFAULT\\b|MODIFY\\s+(?:COLUMN\\s+)?\\S+\\s+.*AUTO_INCREMENT)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private void applyAlter(String statement, List<ParsedTable> tables, List<String> warnings) {
        Matcher head = ALTER_HEAD.matcher(statement.strip());
        if (!head.matches()) {
            warnings.add("Did not apply an ALTER TABLE statement it could not read: " + snippet(statement));
            return;
        }
        String tableName = unquote(head.group(1));
        int index = indexOfTable(tables, tableName);
        if (index < 0) {
            warnings.add("Did not apply an ALTER TABLE on '" + tableName
                    + "': the file has no CREATE TABLE for it. Statement: " + snippet(statement));
            return;
        }
        ParsedTable table = tables.get(index);
        List<ParsedColumn> columns = new ArrayList<>(table.columns());
        List<KnownSchema.ForeignKey> foreignKeys = new ArrayList<>(table.foreignKeys());
        List<String> unapplied = new ArrayList<>();

        for (String clause : splitTopLevel(head.group(2))) {
            Matcher fk = ADD_FOREIGN_KEY.matcher(clause);
            Matcher pk = ADD_PRIMARY_KEY.matcher(clause);
            Matcher unique = ADD_UNIQUE.matcher(clause);
            if (fk.find()) {
                List<String> from = splitArgs(fk.group(1));
                List<String> to = splitArgs(fk.group(3));
                String toTable = unquote(fk.group(2));
                for (int i = 0; i < from.size() && i < to.size(); i++) {
                    foreignKeys.add(new KnownSchema.ForeignKey(table.name(), unquote(from.get(i)), toTable, unquote(to.get(i))));
                }
            } else if (pk.find()) {
                for (String name : splitArgs(pk.group(1))) {
                    replaceColumn(columns, unquote(name), c -> new ParsedColumn(c.name(), c.type(), c.typeArgs(),
                            true, c.autoIncrement(), c.unique(), true));
                }
            } else if (unique.find() && splitArgs(unique.group(1)).size() == 1) {
                replaceColumn(columns, unquote(splitArgs(unique.group(1)).get(0)), c -> new ParsedColumn(c.name(),
                        c.type(), c.typeArgs(), c.notNull(), c.autoIncrement(), true, c.primaryKey()));
            } else if (!NO_EFFECT_ON_SHAPE.matcher(clause).find()) {
                unapplied.add(clause.replaceAll("\\s+", " ").trim());
            }
        }
        if (!unapplied.isEmpty()) {
            warnings.add("Did not apply part of an ALTER TABLE on '" + table.name() + "' (" + String.join("; ", unapplied)
                    + "): QuerySense reads ADD PRIMARY KEY, ADD UNIQUE and ADD FOREIGN KEY there, and nothing else.");
        }
        tables.set(index, new ParsedTable(table.name(), List.copyOf(columns), List.copyOf(foreignKeys),
                table.embeddedArrays(), table.validator()));
    }

    private static int indexOfTable(List<ParsedTable> tables, String name) {
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void replaceColumn(List<ParsedColumn> columns, String name,
                                      java.util.function.UnaryOperator<ParsedColumn> change) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                columns.set(i, change.apply(columns.get(i)));
            }
        }
    }

    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (char c : text.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                if (!current.toString().isBlank()) {
                    out.add(current.toString().strip());
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String last = current.toString().strip();
        if (last.endsWith(";")) {
            last = last.substring(0, last.length() - 1).strip();
        }
        if (!last.isEmpty()) {
            out.add(last);
        }
        return out;
    }

    private boolean startsAlterTable(String statement) {
        String head = statement.stripLeading().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return head.startsWith("ALTER TABLE");
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
            Map<String, KnownSchema.ForeignKey> distinct = new LinkedHashMap<>();
            table.foreignKeys().forEach(fk -> distinct.putIfAbsent(
                    key(fk.fromTable(), fk.fromColumn()) + ">" + key(fk.toTable(), fk.toColumn()), fk));
            out.add(new ParsedTable(table.name(), columns, List.copyOf(distinct.values()), table.embeddedArrays(),
                    table.validator()));
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
            String type = index.getType() == null ? "" : index.getType().toUpperCase(Locale.ROOT);
            if (type.contains("UNIQUE") && index.getColumnsNames().size() == 1) {
                replaceColumn(columns, unquote(index.getColumnsNames().get(0)), c -> new ParsedColumn(c.name(),
                        c.type(), c.typeArgs(), c.notNull(), c.autoIncrement(), true, c.primaryKey()));
                continue;
            }
            if (!type.contains("PRIMARY")) {
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
