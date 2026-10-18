package com.querysense.backend.schema.ddl;

import java.util.List;

/** The literal rows of one INSERT statement in an uploaded schema file. */
public record SampleInsert(String table, List<String> columns, List<List<Object>> rows, int skippedRows) {}
