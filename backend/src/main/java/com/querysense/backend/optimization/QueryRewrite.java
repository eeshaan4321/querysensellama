package com.querysense.backend.optimization;

import java.util.List;

/** The result of rewriting a query: the SQL, and the rules that changed it. */
public record QueryRewrite(String sql, List<RewriteChange> changes) {

    public static QueryRewrite unchanged(String sql) {
        return new QueryRewrite(sql, List.of());
    }

    public boolean changed() {
        return !changes.isEmpty();
    }
}
