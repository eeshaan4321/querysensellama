package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
            sql.append(intent.distinct() ? "SELECT DISTINCT " : "SELECT ")
                    .append(intent.selectItems().stream()
                            .map(item -> operand(item.operand()))
                            .collect(Collectors.joining(", ")))
                    .append(" FROM ")
                    .append(intent.fromTable());
        }

        boolean anti = intent.join() != null && intent.join().kind() == QueryIntent.JoinKind.ANTI;
        if (intent.join() != null) {
            sql.append(anti ? " LEFT JOIN " : " JOIN ").append(intent.join().table())
                    .append(" ON ").append(column(intent.join().left()))
                    .append(" = ").append(column(intent.join().right()));
        }

        List<String> conditions = new ArrayList<>();
        if (!intent.where().isEmpty()) {
            conditions.add(intent.anyOf() && intent.where().size() > 1
                    ? "(" + render(intent.where(), " OR ") + ")"
                    : render(intent.where(), " AND "));
        }
        if (anti) {
            conditions.add(column(intent.join().right()) + " IS NULL");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
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
            sql.append(" HAVING ").append(render(intent.having(), " AND "));
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

    private String render(List<QueryIntent.Predicate> predicates, String joiner) {
        return predicates.stream().map(this::predicate).collect(Collectors.joining(joiner));
    }

    private String predicate(QueryIntent.Predicate p) {
        String lhs = operand(p.lhs());
        return switch (p.op()) {
            case IN -> lhs + " IN " + p.values().stream()
                    .map(v -> "'" + v.text() + "'")
                    .collect(Collectors.joining(", ", "(", ")"));
            case NOT_IN -> lhs + " NOT IN " + p.values().stream()
                    .map(SqlEmitter::literal)
                    .collect(Collectors.joining(", ", "(", ")"));
            case BETWEEN -> lhs + " BETWEEN " + literal(p.values().get(0)) + " AND " + literal(p.values().get(1));
            default -> lhs + " " + p.op().sql() + " " + literal(p.values().get(0));
        };
    }

    public static String column(QueryIntent.ColumnRef ref) {
        return ref.table() == null ? ref.column() : ref.table() + "." + ref.column();
    }

    private static String operand(QueryIntent.Operand operand) {
        return switch (operand) {
            case QueryIntent.Star star -> "*";
            case QueryIntent.TableStar star -> star.table() + ".*";
            case QueryIntent.ColumnRef ref -> column(ref);
            case QueryIntent.Aggregate agg -> agg.fn().name() + "("
                    + (agg.column() == null ? "*" : column(agg.column())) + ")";
        };
    }

    private static String literal(QueryIntent.Literal literal) {
        return literal.numeric() ? literal.text() : "'" + literal.text() + "'";
    }
}
