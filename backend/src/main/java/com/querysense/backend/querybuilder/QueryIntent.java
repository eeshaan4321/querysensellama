package com.querysense.backend.querybuilder;

import java.util.List;

/** A question resolved against the active schema, ready to be written as SQL. */
public record QueryIntent(
        Verb verb,
        String fromTable,
        JoinSpec join,
        List<SelectItem> selectItems,
        List<Predicate> where,
        List<Predicate> having,
        List<String> orderBy,
        Integer limit) {

    public enum Verb { SELECT, DELETE }

    public record JoinSpec(String joinedTable, String leftColumn, String rightColumn) {}

    public record SelectItem(String column, String aggregate) {
        public boolean aggregated() {
            return aggregate != null;
        }

        public String render() {
            return aggregated() ? aggregate + "(" + column + ")" : column;
        }
    }

    public record Predicate(String lhs, String operator, String literal, boolean quoted) {
        public String render() {
            return lhs + " " + operator + " " + (quoted ? "'" + literal + "'" : literal);
        }
    }

    public boolean hasAggregate() {
        return selectItems.stream().anyMatch(SelectItem::aggregated);
    }
}
