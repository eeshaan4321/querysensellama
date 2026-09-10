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

/**
 * SqlPreflightValidator
 *
 * WHY THIS CLASS EXISTS:
 * Two failure modes used to reach EXPLAIN and die there with a raw JDBC
 * error the user can't act on: a GROUP BY that violates MySQL's default
 * ONLY_FULL_GROUP_BY strict mode, and a column the LLM hallucinated (the
 * fallback's prompt is schema-grounded in OllamaService, but this is the
 * safety net for when the model ignores that grounding). Both are
 * detectable from the SQL text alone, before ever touching the database —
 * catching them here means the
 * pipeline fails fast with a specific, actionable message instead of a
 * generic EXPLAIN crash. MySqlExplainAnalyzer keeps its own catch-all for
 * anything this class doesn't cover.
 *
 * SCOPE: like SqlStructureParser, this is a best-effort MVP check, not a
 * full SQL semantics validator — it only looks at simple SELECT/WHERE/
 * GROUP BY shapes and never throws; an unparseable or unusual query just
 * skips these checks and is left for MySqlExplainAnalyzer to report.
 */
@Component
public class SqlPreflightValidator {

    private final SchemaMetadataService schemaMetadataService;

    public SqlPreflightValidator(SchemaMetadataService schemaMetadataService) {
        this.schemaMetadataService = schemaMetadataService;
    }

    /**
     * @return a human-readable failure reason if the query should be blocked
     *         from ever reaching EXPLAIN, or empty if it looks safe to try.
     */
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
            // SqlStructureParser already surfaces parse failures of its own;
            // nothing extra for this class to add.
            return Optional.empty();
        }
    }

    /**
     * Flags any non-aggregated, non-grouped column in the SELECT list —
     * exactly what MySQL's ONLY_FULL_GROUP_BY mode (the default since 5.7)
     * rejects with error 1055.
     */
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
                continue; // aggregate (or other function) call — exempt from ONLY_FULL_GROUP_BY
            }
            if (expr instanceof Column && !groupedColumns.contains(normalize(expr.toString()))) {
                return Optional.of("This query selects '" + expr + "' without grouping or aggregating it — "
                        + "MySQL's strict mode (ONLY_FULL_GROUP_BY) rejects that. Add it to GROUP BY or wrap it "
                        + "in an aggregate function like MAX() or MIN().");
            }
        }
        return Optional.empty();
    }

    /**
     * Flags a SELECT/WHERE column that doesn't exist in the real schema —
     * what MySQL would reject with error 1054 ("Unknown column").
     */
    private Optional<String> checkKnownColumns(PlainSelect plainSelect, String tableName) {
        List<String> realColumns = schemaMetadataService.getColumnNames(tableName);
        if (realColumns.isEmpty()) {
            return Optional.empty(); // metadata lookup unavailable — don't block on it
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
