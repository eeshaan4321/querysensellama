package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/** Extracts a query's table, type and filtered and ordered columns from its SQL text. */
@Component
public class SqlStructureParser {

    public QueryAnalysisResult parse(String sqlQuery) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MySQL");

        String cleaned = stripTrailingSemicolon(sqlQuery);

        try {
            Statement statement = CCJSqlParserUtil.parse(cleaned);

            if (statement instanceof Select select) {
                handleSelect(select, result);
            } else if (statement instanceof Delete delete) {
                result.setQueryType("DELETE");
                result.setTableName(delete.getTable().getName());
            } else {
                result.setQueryType(statement.getClass().getSimpleName());
                result.addWarning("Structural analysis for this statement type is limited; "
                        + "only SELECT queries get full EXPLAIN-based analysis.");
            }
        } catch (JSQLParserException e) {
            result.addWarning("Could not parse SQL structure: " + e.getMessage());
        }

        return result;
    }

    private void handleSelect(Select select, QueryAnalysisResult result) {
        result.setQueryType("SELECT");

        if (select instanceof PlainSelect plainSelect) {
            if (plainSelect.getFromItem() != null) {
                result.setTableName(plainSelect.getFromItem().toString());
            }

            boolean hasJoin = plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty();
            boolean hasWhere = plainSelect.getWhere() != null;
            boolean hasGroupBy = plainSelect.getGroupBy() != null;
            boolean hasOrderBy = plainSelect.getOrderByElements() != null
                    && !plainSelect.getOrderByElements().isEmpty();
            boolean hasLimit = plainSelect.getLimit() != null;

            if (hasJoin) {
                result.addWarning("Query joins multiple tables — the execution plan is reported "
                        + "per table, so check each table's access type individually.");
            }
            if (!hasWhere && !"DELETE".equals(result.getQueryType())) {
                result.addWarning("Query has no WHERE clause — this may scan the entire table "
                        + "regardless of available indexes.");
            }
            if (hasGroupBy) {
                result.addWarning("Query uses GROUP BY — check EXPLAIN output for "
                        + "'Using temporary' if this feels slow.");
            }
            if (hasOrderBy && !hasLimit) {
                result.addWarning("Query sorts results without a LIMIT — "
                        + "consider adding one if you only need a subset of rows.");
            }

            if (hasWhere) {
                result.setFilteredColumn(extractFirstWhereColumn(plainSelect.getWhere()));
            }
            if (hasOrderBy) {
                result.setOrderByColumn(
                        plainSelect.getOrderByElements().get(0).getExpression().toString());
            }
        } else {
            result.addWarning("Compound SELECT (e.g. UNION) detected — "
                    + "structural analysis is limited to simple SELECT statements in this version.");
        }
    }

    private String extractFirstWhereColumn(Expression where) {
        if (where instanceof AndExpression andExpression) {
            return extractFirstWhereColumn(andExpression.getLeftExpression());
        }
        if (where instanceof ComparisonOperator comparison) {
            return comparison.getLeftExpression().toString();
        }
        if (where instanceof net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<?> list
                && list.size() == 1) {
            return extractFirstWhereColumn(list.get(0));
        }
        if (where instanceof net.sf.jsqlparser.expression.operators.relational.InExpression in) {
            return in.getLeftExpression().toString();
        }
        if (where instanceof net.sf.jsqlparser.expression.operators.relational.Between between) {
            return between.getLeftExpression().toString();
        }
        if (where instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression or) {
            String left = extractFirstWhereColumn(or.getLeftExpression());
            String right = extractFirstWhereColumn(or.getRightExpression());
            return left != null && left.equalsIgnoreCase(right) ? left : null;
        }
        return null;
    }

    private String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}