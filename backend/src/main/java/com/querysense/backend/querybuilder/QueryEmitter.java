package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.DialectAware;

/** Writes a QueryIntent as a query in one dialect. */
public interface QueryEmitter extends DialectAware {
    String emit(QueryIntent intent);
}
