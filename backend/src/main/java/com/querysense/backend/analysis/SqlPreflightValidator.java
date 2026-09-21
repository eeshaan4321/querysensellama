package com.querysense.backend.analysis;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Catches GROUP BY violations and unknown columns before a query reaches EXPLAIN. */
@Component
public class SqlPreflightValidator {

    private final SchemaMetadataService schemaMetadataService;

    public SqlPreflightValidator(SchemaMetadataService schemaMetadataService) {
        this.schemaMetadataService = schemaMetadataService;
    }

    public Optional<String> validate(String sqlQuery, String tableName) {
        try {
            Statement statement = CCJSqlParserUtil.parse(stripTrailingSemicolon(sqlQuery));
            if (!(statement instanceof Select select) || !(select instanceof PlainSelect plainSelect)) {
                return Optional.empty();
            }

            Optional<String> groupByIssue = checkOnlyFullGroupBy(plainSelect);
            if (groupByIssue.isPresent()) {
                return groupByIssue;
            }

            return checkKnownColumns(plainSelect, tableName);

        } catch (JSQLParserException e) {
            return Optional.empty();
        }
    }

    private Optional<String> checkOnlyFullGroupBy(PlainSelect plainSelect) {
        if (plainSelect.getGroupBy() == null) {
            return Optional.empty();
        }

        Set<String> groupedColumns = new HashSet<>();
        for (Object exprObj : plainSelect.getGroupBy().getGroupByExpressionList()) {
            groupedColumns.add(normalize(((Expression) exprObj).toString()));
        }

        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr instanceof Function) {
                continue;
            }
            if (expr instanceof Column && !groupedColumns.contains(normalize(expr.toString()))) {
                return Optional.of("This query selects '" + expr + "' without grouping or aggregating it — "
                        + "MySQL's strict mode (ONLY_FULL_GROUP_BY) rejects that. Add it to GROUP BY or wrap it "
                        + "in an aggregate function like MAX() or MIN().");
            }
        }
        return Optional.empty();
    }

    private Optional<String> checkKnownColumns(PlainSelect plainSelect, String tableName) {
        List<String> realColumns = schemaMetadataService.getColumnNames(tableName);
        if (realColumns.isEmpty()) {
            return Optional.empty();
        }
        Set<String> realColumnsLower = new HashSet<>();
        for (String col : realColumns) {
            realColumnsLower.add(col.toLowerCase());
        }

        Set<String> referenced = new HashSet<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            if (item.getExpression() instanceof Column column) {
                referenced.add(column.getColumnName());
            }
        }
        if (plainSelect.getWhere() != null) {
            collectColumns(plainSelect.getWhere(), referenced);
        }

        for (String col : referenced) {
            if (!realColumnsLower.contains(col.toLowerCase())) {
                return Optional.of("Column '" + col + "' does not exist on '" + tableName
                        + "' — this query references a column that isn't part of the real schema.");
            }
        }
        return Optional.empty();
    }

    private void collectColumns(Expression expr, Set<String> out) {
        if (expr instanceof AndExpression and) {
            collectColumns(and.getLeftExpression(), out);
            collectColumns(and.getRightExpression(), out);
        } else if (expr instanceof ComparisonOperator comparison
                && comparison.getLeftExpression() instanceof Column col) {
            out.add(col.getColumnName());
        }
    }

    private String normalize(String raw) {
        return raw.replaceAll("^\\w+\\.", "").toLowerCase().trim();
    }

    private String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
