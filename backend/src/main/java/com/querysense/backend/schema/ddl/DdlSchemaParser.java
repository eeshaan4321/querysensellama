package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses CREATE TABLE statements from uploaded DDL. */
@Component
public class DdlSchemaParser {

    private static final Logger log = LoggerFactory.getLogger(DdlSchemaParser.class);
    private static final int WARNING_SNIPPET_LENGTH = 80;

    public ParsedSchema parse(String ddl) {
        List<ParsedTable> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String statement : SqlStatementSplitter.split(ddl)) {
            if (!startsCreateTable(statement)) {
                continue;
            }
            try {
                Statement parsed = CCJSqlParserUtil.parse(statement);
                if (parsed instanceof CreateTable createTable) {
                    tables.add(toParsedTable(createTable));
                }
            } catch (Exception e) {
                warnings.add("Skipped a statement that could not be parsed: " + snippet(statement));
                log.warn("Skipping unparseable DDL statement: {}", e.getMessage());
            }
        }

        return new ParsedSchema(List.copyOf(tables), List.copyOf(warnings));
    }

    private boolean startsCreateTable(String statement) {
        String head = statement.stripLeading().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return head.startsWith("CREATE TABLE");
    }

    private ParsedTable toParsedTable(CreateTable createTable) {
        String tableName = unquote(createTable.getTable().getName());
        List<ParsedColumn> columns = new ArrayList<>();
        List<KnownSchema.ForeignKey> foreignKeys = new ArrayList<>();

        List<ColumnDefinition> definitions =
                createTable.getColumnDefinitions() == null ? List.of() : createTable.getColumnDefinitions();

        for (ColumnDefinition definition : definitions) {
            String columnName = unquote(definition.getColumnName());
            List<String> specs = definition.getColumnSpecs() == null ? List.of() : definition.getColumnSpecs();
            String specText = String.join(" ", specs).toUpperCase(Locale.ROOT);

            columns.add(new ParsedColumn(
                    columnName,
                    bareType(definition.getColDataType().getDataType()),
                    typeArgsOf(definition),
                    specText.contains("NOT NULL"),
                    specText.contains("AUTO_INCREMENT"),
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
