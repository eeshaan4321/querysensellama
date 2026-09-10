package com.querysense.backend.querybuilder;

import java.util.List;

/**
 * QueryIntent
 *
 * One question, fully resolved against the ACTIVE schema. Every table and
 * column named here has already been bound to something real — nothing in
 * this record is a raw string from the user.
 *
 * WHY THAT PROPERTY IS THE WHOLE DESIGN:
 * the previous rule engine decided what it could handle by checking the
 * input against a hardcoded English blacklist — `who`, `which`, `more than`,
 * month names. That list could not follow an uploaded schema, so uploading
 * one changed the engine's vocabulary but never what it was willing to
 * claim. Here, "the engine declines correctly" is structural instead: if a
 * phrase could not be bound, there is no intent to emit, and the caller
 * declines. The decision now depends on the schema, which is the point.
 */
public record QueryIntent(
        Verb verb,
        String fromTable,
        JoinSpec join,                 // nullable — single-table query
        List<SelectItem> selectItems,  // empty for DELETE
        List<Predicate> where,
        List<Predicate> having,
        List<String> orderBy,
        Integer limit) {               // nullable

    public enum Verb { SELECT, DELETE }

    /**
     * Column names here are already qualified by the parser when a join is
     * present, and deliberately bare when it is not — an unqualified
     * single-table query keeps this engine's output byte-identical to what
     * it produced before joins existed.
     */
    public record JoinSpec(String joinedTable, String leftColumn, String rightColumn) {}

    /** @param aggregate null for a plain column, else COUNT/SUM/AVG/MIN/MAX. */
    public record SelectItem(String column, String aggregate) {
        public boolean aggregated() {
            return aggregate != null;
        }

        public String render() {
            return aggregated() ? aggregate + "(" + column + ")" : column;
        }
    }

    /**
     * @param quoted whether the literal needs quoting. Decided at bind time
     *               from what the column actually holds, never by guessing
     *               at the literal's shape.
     */
    public record Predicate(String lhs, String operator, String literal, boolean quoted) {
        public String render() {
            return lhs + " " + operator + " " + (quoted ? "'" + literal + "'" : literal);
        }
    }

    public boolean hasAggregate() {
        return selectItems.stream().anyMatch(SelectItem::aggregated);
    }
}
