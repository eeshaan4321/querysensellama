package com.querysense.backend.schema;

import java.util.List;

/** An index on one table or collection: its ordered key parts and whether it is unique. */
public record IndexSpec(String table, List<IndexSpec.KeyPart> keys, boolean unique) {
    public record KeyPart(String column, int direction) {}
}
