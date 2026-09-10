package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SqlEmitter
 *
 * QueryIntent to SQL. Deterministic, and knows no English whatsoever — every
 * decision it makes is structural.
 *
 * WHY THIS IS SEPARATE FROM THE PARSER:
 * the emitted text can then be asserted character for character, without a
 * natural-language phrasing standing between the test and the SQL. The
 * parser's tests are about what binds; this one's are about what comes out.
 *
 * WHY GROUP BY IS DERIVED RATHER THAN BOUND:
 * MySQL runs ONLY_FULL_GROUP_BY by default. Grouping by exactly the
 * non-aggregated projections is always valid under it, whereas grouping by
 * the primary key alone relies on MySQL's functional-dependency detection —
 * true on this project's server today, and a silent failure on any server
 * configured otherwise. SqlPreflightValidator exists in part because that
 * rule was broken before; deriving the clause means it cannot be broken
 * here.
 */
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
            // Only the non-aggregated projections. A bare COUNT(*) with no
            // other projection therefore emits no GROUP BY at all, which is
            // what keeps "How many orders are there" identical to its
            // documented output.
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
