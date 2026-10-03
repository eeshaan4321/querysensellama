package com.querysense.backend.querybuilder;

/** A word in the question read as a schema item through an alias, a synonym or a typo correction. */
public record Interpretation(String phrase, String item, SchemaLexicon.MatchKind kind) {}
