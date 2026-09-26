package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Writes SQL from a QueryIntent. */
@Component
public class SqlEmitter implements QueryEmitter {

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public String emit(QueryIntent intent) {
        StringBuilder sql = new StringBuilder();

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            sql.append("DELETE FROM ").append(intent.fromTable());
        } else {
            sql.append("SELECT ")
                    .append(intent.selectItems().stream()
                            .map(item -> operand(item.operand()))
                            .collect(Collectors.joining(", ")))
                    .append(" FROM ")
                    .append(intent.fromTable());
        }

        if (intent.join() != null) {
            sql.append(" JOIN ").append(intent.join().table())
                    .append(" ON ").append(column(intent.join().left()))
                    .append(" = ").append(column(intent.join().right()));
        }

        if (!intent.where().isEmpty()) {
            sql.append(" WHERE ").append(renderAll(intent.where()));
        }

        if (intent.hasAggregate()) {
            List<String> grouped = intent.selectItems().stream()
                    .filter(item -> !item.aggregated())
                    .map(item -> operand(item.operand()))
                    .toList();
            if (!grouped.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", grouped));
            }
        }

        if (!intent.having().isEmpty()) {
            sql.append(" HAVING ").append(renderAll(intent.having()));
        }

        if (!intent.orderBy().isEmpty()) {
            sql.append(" ORDER BY ").append(intent.orderBy().stream()
                    .map(o -> column(o.column()) + " " + o.direction().name())
                    .collect(Collectors.joining(", ")));
        }

        if (intent.limit() != null) {
            sql.append(" LIMIT ").append(intent.limit());
        }

        return sql.append(";").toString();
    }

    public static String column(QueryIntent.ColumnRef ref) {
        return ref.table() == null ? ref.column() : ref.table() + "." + ref.column();
    }

    private static String operand(QueryIntent.Operand operand) {
        return switch (operand) {
            case QueryIntent.Star star -> "*";
            case QueryIntent.ColumnRef ref -> column(ref);
            case QueryIntent.Aggregate agg -> agg.fn().name() + "("
                    + (agg.column() == null ? "*" : column(agg.column())) + ")";
        };
    }

    private String renderAll(List<QueryIntent.Predicate> predicates) {
        return predicates.stream()
                .map(this::predicate)
                .collect(Collectors.joining(" AND "));
    }

    private String predicate(QueryIntent.Predicate p) {
        String lhs = operand(p.lhs());
        if (p.op() == QueryIntent.Op.IN) {
            return lhs + " IN " + p.values().stream()
                    .map(v -> "'" + v.text() + "'")
                    .collect(Collectors.joining(", ", "(", ")"));
        }
        return lhs + " " + p.op().sql() + " " + literal(p.values().get(0));
    }

    private static String literal(QueryIntent.Literal literal) {
        return literal.numeric() ? literal.text() : "'" + literal.text() + "'";
    }
}
