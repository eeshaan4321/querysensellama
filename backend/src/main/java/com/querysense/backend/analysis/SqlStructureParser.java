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

/**
 * SqlStructureParser
 *
 * WHY THIS CLASS EXISTS:
 * MySqlExplainAnalyzer needs a table name and query type to populate
 * QueryAnalysisResult, but up to now those were passed in manually (see
 * the ExplainTestController manual test). In the real pipeline, the only
 * thing available is the raw SQL string coming out of RuleBasedQueryBuilder
 * or OllamaService. This class closes that gap: it parses the SQL text
 * itself (via JSQL Parser) and extracts the structural facts other
 * modules need, instead of every caller re-implementing string parsing.
 *
 * WHY JSQL Parser instead of regex:
 * RuleBasedQueryBuilder already uses some regex for simple pattern
 * matching during generation, which is fine for template-driven NL
 * parsing. But once we HAVE a real SQL string (either from the rule
 * engine or from the LLM fallback), parsing it with regex again is fragile —
 * table names can appear in subqueries, WHERE clauses can contain the
 * word "FROM" in a string literal, etc. JSQL Parser gives us a proper
 * AST, so extraction is structural, not pattern-matched.
 *
 * SCOPE NOTE:
 * This only reads structure — it does not run or validate the query
 * against a real database (that's MySqlExplainAnalyzer's job). This
 * class should never fail loudly on a query it can't fully understand;
 * it should degrade to "unknown" fields plus a warning, since the
 * calling pipeline (QueryAnalysisService, Task 5) still needs to
 * continue even for queries with unusual structure.
 */
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
                // INSERT/UPDATE/other — out of scope for analysis in this
                // module per the synopsis (analysis engine targets SELECT
                // primarily, with DELETE needing a WHERE clause per
                // RuleBasedQueryBuilder's existing safety rule).
                result.setQueryType(statement.getClass().getSimpleName());
                result.addWarning("Structural analysis for this statement type is limited; "
                        + "only SELECT queries get full EXPLAIN-based analysis.");
            }
        } catch (JSQLParserException e) {
            // WHY we don't throw here: a query the parser can't handle
            // shouldn't crash the whole analysis pipeline. QueryAnalysisService
            // (Task 5) needs a result object back either way, even if it's
            // mostly empty, so it can still return a coherent response.
            result.addWarning("Could not parse SQL structure: " + e.getMessage());
        }

        return result;
    }

    private void handleSelect(Select select, QueryAnalysisResult result) {
        result.setQueryType("SELECT");

        // JSQL Parser 5.x: Select IS the top-level statement; for a plain
        // (non-UNION) query it can be cast directly to PlainSelect to reach
        // FROM/WHERE/JOIN/GROUP BY/ORDER BY/LIMIT.
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
                // WHY the wording changed: this used to say only the primary
                // table's plan was analyzed, which was true of the old flat
                // analyzer. MySqlExplainAnalyzer now keeps every EXPLAIN row,
                // so the statement would be false — and a warning that
                // understates what the tool did is as misleading as one that
                // overstates it.
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
            // UNION or other compound SELECT — out of scope for MVP structural analysis.
            result.addWarning("Compound SELECT (e.g. UNION) detected — "
                    + "structural analysis is limited to simple SELECT statements in this version.");
        }
    }

    /**
     * Extracts the left-hand column name from a simple WHERE clause
     * (e.g. "city = 'Mumbai'" -> "city"). For an AND-chain, takes the
     * first condition, since that's enough signal for an index
     * recommendation — this is not a full expression evaluator, just a
     * best-effort column hint for the Schema Advisor. Anything more
     * complex (OR, subqueries, function calls) degrades to null; the
     * caller must handle a missing column name gracefully.
     */
    private String extractFirstWhereColumn(Expression where) {
        if (where instanceof AndExpression andExpression) {
            return extractFirstWhereColumn(andExpression.getLeftExpression());
        }
        if (where instanceof ComparisonOperator comparison) {
            return comparison.getLeftExpression().toString();
        }
        return null;
    }

    private String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}