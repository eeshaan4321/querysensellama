package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.MongoPreflightValidator;
import com.querysense.backend.analysis.MongoQueryFields;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Flags LLM-generated SQL that appears to miss part of the question. */
@Component
public class GeneratedQueryValidator {

    private final ActiveSchemaProvider activeSchema;
    private final MongoShellParser mongoParser;
    private final MongoPreflightValidator mongoPreflight;

    @Autowired
    public GeneratedQueryValidator(ActiveSchemaProvider activeSchema,
                                   MongoShellParser mongoParser,
                                   MongoPreflightValidator mongoPreflight) {
        this.activeSchema = activeSchema;
        this.mongoParser = mongoParser;
        this.mongoPreflight = mongoPreflight;
    }

    public GeneratedQueryValidator(ActiveSchemaProvider activeSchema) {
        this(activeSchema, new MongoShellParser(), new MongoPreflightValidator(activeSchema));
    }

    private static final Pattern MULTI_CONDITION_LANGUAGE = Pattern.compile(
            ".*\\b(with|who\\s+have|who\\s+placed|who\\s+has|and)\\b.*"
    );
    private static final Pattern QUANTITATIVE_LANGUAGE = Pattern.compile(
            ".*(\\d+|more\\s+than|less\\s+than|greater\\s+than|at\\s+least|at\\s+most|over|under)\\b.*"
    );

    public boolean looksIncomplete(String userInput, String generatedQuery, String databaseType) {
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return userInput != null && generatedQuery != null && mongoLooksIncomplete(userInput, generatedQuery);
        }
        if (!"MySQL".equalsIgnoreCase(databaseType) || userInput == null || generatedQuery == null) {
            return false;
        }

        String sql = generatedQuery.toLowerCase();

        List<KnownSchema.Table> mentionedTables = activeSchema.detectTablesMentioned(Dialect.MYSQL, userInput);
        if (mentionedTables.size() > 1) {
            boolean sqlReferencesEveryMentionedTable = mentionedTables.stream()
                    .allMatch(t -> sql.contains(t.name()) || sql.contains(t.singularName()));
            if (!sqlReferencesEveryMentionedTable) {
                return true;
            }
        }

        String input = userInput.toLowerCase();
        boolean impliesMultipleConditions =
                MULTI_CONDITION_LANGUAGE.matcher(input).matches() && QUANTITATIVE_LANGUAGE.matcher(input).matches();
        if (impliesMultipleConditions) {
            boolean showsComparableComplexity =
                    sql.contains("join") || sql.contains("having") || sql.contains("group by") || sql.contains(" and ");
            return !showsComparableComplexity;
        }

        return false;
    }

    public Optional<String> namesUnknownIdentifiers(String generatedQuery, String databaseType) {
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return generatedQuery == null ? Optional.empty() : mongoIssue(generatedQuery);
        }
        if (!"MySQL".equalsIgnoreCase(databaseType) || generatedQuery == null) {
            return Optional.empty();
        }
        if (activeSchema.tables(Dialect.MYSQL).isEmpty()) {
            return Optional.empty();
        }

        PlainSelect select;
        try {
            Statement statement = CCJSqlParserUtil.parse(stripTrailingSemicolon(generatedQuery));
            if (!(statement instanceof Select parsed) || !(parsed instanceof PlainSelect plainSelect)) {
                return Optional.empty();
            }
            select = plainSelect;
        } catch (JSQLParserException e) {
            return Optional.empty();
        }

        Map<String, KnownSchema.Table> byQualifier = new LinkedHashMap<>();
        List<KnownSchema.Table> resolved = new ArrayList<>();

        for (Table table : tablesOf(select)) {
            Optional<KnownSchema.Table> known = activeSchema.findTable(Dialect.MYSQL, table.getName());
            if (known.isEmpty()) {
                return Optional.of("'" + table.getName() + "' is not a table in the schema that is "
                        + "currently active, so this query cannot run against it.");
            }
            resolved.add(known.get());
            byQualifier.put(table.getName().toLowerCase(), known.get());
            if (table.getAlias() != null) {
                byQualifier.put(table.getAlias().getName().toLowerCase(), known.get());
            }
        }

        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        for (Column column : referencedColumns(select)) {
            String name = column.getColumnName();
            if (name == null || "*".equals(name)) {
                continue;
            }

            String qualifier = column.getTable() != null ? column.getTable().getName() : null;
            if (qualifier != null) {
                KnownSchema.Table owner = byQualifier.get(qualifier.toLowerCase());
                if (owner == null) {
                    return Optional.of("'" + qualifier + "' is not one of the tables this query reads from.");
                }
                if (!hasColumn(owner, name)) {
                    return Optional.of("'" + name + "' is not a column of '" + owner.name() + "'.");
                }
            } else if (resolved.stream().noneMatch(t -> hasColumn(t, name))) {
                return Optional.of("'" + name + "' is not a column of "
                        + String.join(" or ", resolved.stream().map(KnownSchema.Table::name).toList()) + ".");
            }
        }

        return Optional.empty();
    }

    private Optional<String> mongoIssue(String generatedQuery) {
        MongoQuery query;
        try {
            query = mongoParser.parse(generatedQuery);
        } catch (MongoShellParseException e) {
            return Optional.of("This is not a MongoDB query QuerySense can read: " + e.getMessage());
        }
        return mongoPreflight.validate(query);
    }

    private boolean mongoLooksIncomplete(String userInput, String generatedQuery) {
        List<KnownSchema.Table> mentioned = activeSchema.detectTablesMentioned(Dialect.MONGODB, userInput);
        if (mentioned.size() < 2) {
            return false;
        }
        Set<String> names = new LinkedHashSet<>();
        mentioned.forEach(t -> names.add(t.name()));
        boolean related = activeSchema.foreignKeys(Dialect.MONGODB).stream()
                .anyMatch(fk -> !fk.fromTable().equals(fk.toTable())
                        && names.contains(fk.fromTable()) && names.contains(fk.toTable()));
        if (!related) {
            return false;
        }

        MongoQuery query;
        try {
            query = mongoParser.parse(generatedQuery);
        } catch (MongoShellParseException e) {
            return false;
        }
        boolean joins = query.pipeline() != null
                && query.pipeline().stream().anyMatch(stage -> stage.containsKey("$lookup"));
        if (joins) {
            return false;
        }
        List<String> arrays = activeSchema.tables(Dialect.MONGODB).stream()
                .filter(t -> t.name().equals(query.collection()))
                .findFirst()
                .map(KnownSchema.Table::embeddedArrays)
                .orElse(List.of());
        boolean readsEmbedded = MongoQueryFields.referencedPaths(query).stream()
                .anyMatch(path -> arrays.stream().anyMatch(a -> path.equals(a) || path.startsWith(a + ".")));
        return !readsEmbedded;
    }

    private List<Table> tablesOf(PlainSelect select) {
        List<Table> tables = new ArrayList<>();
        if (select.getFromItem() instanceof Table from) {
            tables.add(from);
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getRightItem() instanceof Table joined) {
                    tables.add(joined);
                }
            }
        }
        return tables;
    }

    private Set<Column> referencedColumns(PlainSelect select) {
        Set<Column> columns = new LinkedHashSet<>();

        for (SelectItem<?> item : select.getSelectItems()) {
            collectColumns(item.getExpression(), columns);
        }
        collectColumns(select.getWhere(), columns);
        collectColumns(select.getHaving(), columns);

        if (select.getGroupBy() != null) {
            for (Object expression : select.getGroupBy().getGroupByExpressionList()) {
                collectColumns((Expression) expression, columns);
            }
        }
        if (select.getOrderByElements() != null) {
            for (OrderByElement element : select.getOrderByElements()) {
                collectColumns(element.getExpression(), columns);
            }
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        collectColumns(on, columns);
                    }
                }
            }
        }
        return columns;
    }

    private void collectColumns(Expression expression, Set<Column> out) {
        if (expression == null) {
            return;
        }
        if (expression instanceof Column column) {
            out.add(column);
        } else if (expression instanceof BinaryExpression binary) {
            collectColumns(binary.getLeftExpression(), out);
            collectColumns(binary.getRightExpression(), out);
        } else if (expression instanceof Function function) {
            if (function.getParameters() != null) {
                for (Object parameter : function.getParameters()) {
                    collectColumns((Expression) parameter, out);
                }
            }
        } else if (expression instanceof Parenthesis parenthesis) {
            collectColumns(parenthesis.getExpression(), out);
        } else if (expression instanceof InExpression in) {
            collectColumns(in.getLeftExpression(), out);
        } else if (expression instanceof IsNullExpression isNull) {
            collectColumns(isNull.getLeftExpression(), out);
        } else if (expression instanceof Between between) {
            collectColumns(between.getLeftExpression(), out);
        }
    }

    private boolean hasColumn(KnownSchema.Table table, String column) {
        return table.columns().stream().anyMatch(c -> c.equalsIgnoreCase(column));
    }

    private String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
