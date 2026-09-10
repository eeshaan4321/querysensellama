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

/**
 * DdlSchemaParser
 *
 * Turns uploaded DDL into the structural model the rest of the upload
 * pipeline works from. Reads CREATE TABLE and nothing else.
 *
 * WHY UNPARSEABLE STATEMENTS BECOME WARNINGS RATHER THAN ERRORS:
 * A real schema dump carries plenty this parser has no interest in, and
 * some of it JSqlParser cannot read. Failing the upload because statement
 * 41 of 300 was a LOCK TABLES would make the feature useless on exactly the
 * files people actually have. What was skipped is reported.
 *
 * WHY NOTHING HERE TOUCHES THE DATABASE:
 * Parsing is separated from provisioning so all of it is unit-testable
 * without MySQL, and — more importantly — so validation gets to run on the
 * parsed model BEFORE anything is executed.
 */
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

    /**
     * Only statements that announce themselves as CREATE TABLE are handed to
     * JSqlParser. Without this, every INSERT and SET in a dump would be
     * parsed and then discarded — and any of them that failed to parse would
     * produce a warning about a statement the user never cared about.
     */
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

    /**
     * WHY THIS READS THE TYPE TWO DIFFERENT WAYS:
     * JSqlParser 5.3 is not consistent about where a column's type arguments
     * live, and this was verified against the library rather than assumed:
     *
     *   ENUM('NEW','PAID')  -> getDataType()="ENUM",
     *                          getArgumentsStringList()=['NEW','PAID']
     *   VARCHAR(120)        -> getDataType()="VARCHAR (120)",
     *                          getArgumentsStringList()=null
     *   DECIMAL(10,2)       -> getDataType()="DECIMAL (10, 2)",  args=null
     *
     * Trusting only the accessor loses every length and scale; trusting only
     * the type string loses every ENUM's values. Both matter downstream —
     * SchemaSeeder sizes VARCHARs, rounds DECIMALs to their declared scale,
     * and can only pick a legal ENUM value if it knows the members. So the
     * accessor wins when it has something, and the type string is parsed
     * otherwise.
     */
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

    /**
     * Splits on commas that are not inside a quoted literal. ENUM values are
     * the reason: ENUM('A,B','C') has two members, not three, and a naive
     * split would invent a third value the column can never hold.
     */
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

    /**
     * `PRIMARY KEY (id)` written as a table-level constraint does not appear
     * in the column's own spec list, so a column declared that way would
     * look non-primary — and the seeder would then generate random values
     * for it instead of unique sequential ones. This walks the table's
     * indexes and back-fills the flag.
     */
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

    /** Handles {@code customer_id INT REFERENCES customers(id)}. */
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

    /** Strips backticks, double quotes and any schema qualifier. */
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
