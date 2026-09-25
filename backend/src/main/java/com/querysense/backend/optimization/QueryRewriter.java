package com.querysense.backend.optimization;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rewrites a SELECT into an equivalent one that MySQL can serve more cheaply.
 * Every rule here preserves the rows returned; a rule that cannot guarantee that declines.
 */
@Component
public class QueryRewriter {

    private final ActiveSchemaProvider activeSchema;

    public QueryRewriter(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public QueryRewrite rewrite(String sql, String databaseType) {
        if (sql == null || !"MySQL".equalsIgnoreCase(databaseType)) {
            return QueryRewrite.unchanged(sql);
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return QueryRewrite.unchanged(sql);
        }

        if (!(statement instanceof Select select) || !(select.getSelectBody() instanceof PlainSelect plain)) {
            return QueryRewrite.unchanged(sql);
        }

        List<RewriteChange> changes = new ArrayList<>();
        expandSelectStar(plain, changes);
        collapseOrChains(plain, changes);
        dropPointlessOrderBy(plain, changes);

        if (changes.isEmpty()) {
            return QueryRewrite.unchanged(sql);
        }

        String rewritten = plain.toString();
        if (sql.trim().endsWith(";")) {
            rewritten = rewritten + ";";
        }
        return new QueryRewrite(rewritten, List.copyOf(changes));
    }

    /** RW1 — name the columns, so the query can be served from a covering index. */
    private void expandSelectStar(PlainSelect plain, List<RewriteChange> changes) {
        List<SelectItem<?>> items = plain.getSelectItems();
        if (items == null || items.size() != 1) return;
        if (!"*".equals(items.get(0).getExpression().toString())) return;
        if (plain.getJoins() != null && !plain.getJoins().isEmpty()) return;

        Optional<KnownSchema.Table> table = singleTable(plain.getFromItem());
        if (table.isEmpty()) return;

        List<SelectItem<?>> expanded = new ArrayList<>();
        for (String column : table.get().columns()) {
            expanded.add(new SelectItem<>(new Column(column)));
        }
        plain.setSelectItems(expanded);

        changes.add(new RewriteChange("RW1",
                "Named the " + table.get().columns().size() + " columns of '" + table.get().name()
                        + "' instead of SELECT *, so an index covering them can answer the query "
                        + "without reading the row. The rows returned are identical."));
    }

    /** RW2 — one column compared to several constants is an IN list, which an index can range-scan. */
    private void collapseOrChains(PlainSelect plain, List<RewriteChange> changes) {
        Expression where = plain.getWhere();
        if (!(where instanceof OrExpression)) return;

        Map<String, List<Expression>> byColumn = new LinkedHashMap<>();
        if (!flattenEqualities(where, byColumn)) return;
        if (byColumn.size() != 1) return;

        Map.Entry<String, List<Expression>> only = byColumn.entrySet().iterator().next();
        if (only.getValue().size() < 2) return;

        InExpression in = new InExpression(
                new Column(only.getKey()), new ParenthesedExpressionList<>(only.getValue()));
        plain.setWhere(in);

        changes.add(new RewriteChange("RW2",
                "Collapsed " + only.getValue().size() + " OR'd equality tests on '" + only.getKey()
                        + "' into a single IN list. MySQL can range-scan an index for IN, where a chain "
                        + "of ORs on the same column often falls back to a scan. The rows returned are identical."));
    }

    /** RW3 — an aggregate with no GROUP BY returns one row, so sorting it does nothing. */
    private void dropPointlessOrderBy(PlainSelect plain, List<RewriteChange> changes) {
        if (plain.getOrderByElements() == null || plain.getOrderByElements().isEmpty()) return;
        if (plain.getGroupBy() != null) return;
        if (plain.getLimit() != null) return;

        List<SelectItem<?>> items = plain.getSelectItems();
        if (items == null || items.size() != 1) return;
        if (!isAggregate(items.get(0))) return;

        plain.setOrderByElements(null);
        changes.add(new RewriteChange("RW3",
                "Dropped ORDER BY from an aggregate with no GROUP BY. The query returns a single row, "
                        + "so the sort could not change the result — it only cost a filesort."));
    }

    private boolean isAggregate(SelectItem<?> item) {
        String expression = item.getExpression().toString().toUpperCase();
        return expression.startsWith("COUNT(")
                || expression.startsWith("SUM(")
                || expression.startsWith("AVG(")
                || expression.startsWith("MIN(")
                || expression.startsWith("MAX(");
    }

    /** Collects `col = constant` leaves joined only by OR; false if anything else appears. */
    private boolean flattenEqualities(Expression expression, Map<String, List<Expression>> byColumn) {
        if (expression instanceof OrExpression or) {
            return flattenEqualities(or.getLeftExpression(), byColumn)
                    && flattenEqualities(or.getRightExpression(), byColumn);
        }
        if (expression instanceof EqualsTo equals
                && equals.getLeftExpression() instanceof Column column
                && !(equals.getRightExpression() instanceof Column)) {
            byColumn.computeIfAbsent(column.getColumnName().toLowerCase(), k -> new ArrayList<>())
                    .add(equals.getRightExpression());
            return true;
        }
        return false;
    }

    private Optional<KnownSchema.Table> singleTable(FromItem fromItem) {
        if (!(fromItem instanceof Table table)) return Optional.empty();
        return activeSchema.findTable(table.getName());
    }
}
