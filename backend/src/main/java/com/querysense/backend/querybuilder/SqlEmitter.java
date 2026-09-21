package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Writes SQL from a QueryIntent. */
@Component
public class SqlEmitter {

    public String emit(QueryIntent intent) {
        StringBuilder sql = new StringBuilder();

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            sql.append("DELETE FROM ").append(intent.fromTable());
        } else {
            sql.append("SELECT ")
                    .append(intent.selectItems().stream()
                            .map(QueryIntent.SelectItem::render)
                            .collect(Collectors.joining(", ")))
                    .append(" FROM ")
                    .append(intent.fromTable());
        }

        if (intent.join() != null) {
            sql.append(" JOIN ").append(intent.join().joinedTable())
                    .append(" ON ").append(intent.join().leftColumn())
                    .append(" = ").append(intent.join().rightColumn());
        }

        if (!intent.where().isEmpty()) {
            sql.append(" WHERE ").append(renderAll(intent.where()));
        }

        if (intent.hasAggregate()) {
            List<String> grouped = intent.selectItems().stream()
                    .filter(item -> !item.aggregated())
                    .map(QueryIntent.SelectItem::column)
                    .toList();
            if (!grouped.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", grouped));
            }
        }

        if (!intent.having().isEmpty()) {
            sql.append(" HAVING ").append(renderAll(intent.having()));
        }

        if (!intent.orderBy().isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", intent.orderBy()));
        }

        if (intent.limit() != null) {
            sql.append(" LIMIT ").append(intent.limit());
        }

        return sql.append(";").toString();
    }

    private String renderAll(List<QueryIntent.Predicate> predicates) {
        return predicates.stream()
                .map(QueryIntent.Predicate::render)
                .collect(Collectors.joining(" AND "));
    }
}
