package com.querysense.backend.optimization;

import com.querysense.backend.dialect.DialectAware;

/** Rewrites one dialect's query into an equivalent, cheaper one, or returns it unchanged. */
public interface Rewriter extends DialectAware {
    QueryRewrite rewrite(String query);
}
