package com.querysense.backend.querybuilder;

import java.math.BigDecimal;
import java.util.List;

/** A question resolved against the active schema, in a form any dialect's emitter can write. */
public record QueryIntent(
        Verb verb,
        String fromTable,
        JoinSpec join,
        List<SelectItem> selectItems,
        List<Predicate> where,
        List<Predicate> having,
        List<OrderItem> orderBy,
        Integer limit,
        boolean distinct,
        boolean anyOf) {

    public QueryIntent(Verb verb, String fromTable, JoinSpec join, List<SelectItem> selectItems,
                       List<Predicate> where, List<Predicate> having, List<OrderItem> orderBy, Integer limit) {
        this(verb, fromTable, join, selectItems, where, having, orderBy, limit, false, false);
    }

    public enum Verb { SELECT, DELETE }

    public enum Fn { COUNT, SUM, AVG, MIN, MAX }

    public enum Direction { ASC, DESC }

    public enum Op {
        EQ("="), GT(">"), GTE(">="), LT("<"), LTE("<="), IN("IN"), NE("!="), NOT_IN("NOT IN"), BETWEEN("BETWEEN");

        private final String sql;

        Op(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    public sealed interface Operand permits ColumnRef, Aggregate, Star, TableStar {}

    public record ColumnRef(String table, String column) implements Operand {}

    public record Aggregate(Fn fn, ColumnRef column) implements Operand {}

    public enum Star implements Operand { INSTANCE }

    public record TableStar(String table) implements Operand {}

    public enum JoinKind { INNER, ANTI }

    public record Literal(String text, boolean numeric, Object value) {
        public static Literal number(int n) {
            return new Literal(String.valueOf(n), true, n);
        }

        public static Literal number(BigDecimal n) {
            BigDecimal plain = n.stripTrailingZeros();
            Object value;
            if (plain.scale() <= 0 && plain.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
                long whole = plain.longValueExact();
                value = whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE ? (Object) (int) whole : (Object) whole;
            } else {
                value = plain.doubleValue();
            }
            return new Literal(plain.toPlainString(), true, value);
        }

        public static Literal text(String typed, String stored) {
            return new Literal(typed, false, stored);
        }
    }

    public record SelectItem(Operand operand) {
        public boolean aggregated() {
            return operand instanceof Aggregate;
        }
    }

    public record Predicate(Operand lhs, Op op, List<Literal> values) {}

    public record OrderItem(ColumnRef column, Direction direction) {}

    public record JoinSpec(String table, ColumnRef left, ColumnRef right, JoinKind kind) {
        public JoinSpec(String table, ColumnRef left, ColumnRef right) {
            this(table, left, right, JoinKind.INNER);
        }
    }

    public boolean hasAggregate() {
        return selectItems.stream().anyMatch(SelectItem::aggregated);
    }
}
