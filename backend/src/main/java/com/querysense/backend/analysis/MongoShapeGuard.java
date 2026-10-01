package com.querysense.backend.analysis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The explain stages and statistics MongoExplainParser understands; anything else is reported, not guessed at. */
final class MongoShapeGuard {

    static final Set<String> SCAN_STAGES = Set.of("COLLSCAN");

    static final Set<String> INDEX_STAGES = Set.of(
            "IXSCAN", "EXPRESS_IXSCAN", "EXPRESS_IDHACK", "IDHACK", "COUNT_SCAN", "DISTINCT_SCAN",
            "CLUSTERED_IXSCAN", "EXPRESS_CLUSTERED_IXSCAN");

    static final Set<String> KNOWN_STAGES;

    static final List<String> REQUIRED_STATS = List.of("nReturned", "totalDocsExamined", "executionTimeMillis");

    static {
        Set<String> known = new HashSet<>(SCAN_STAGES);
        known.addAll(INDEX_STAGES);
        known.addAll(Set.of(
                "FETCH", "SORT", "SORT_KEY_GENERATOR", "SORT_MERGE", "PROJECTION_SIMPLE", "PROJECTION_DEFAULT",
                "PROJECTION_COVERED", "LIMIT", "SKIP", "OR", "AND_HASH", "AND_SORTED", "COUNT", "BATCHED_DELETE",
                "DELETE", "GROUP", "EQ_LOOKUP", "EQ_LOOKUP_UNWIND", "UNWIND", "SHARDING_FILTER", "EOF", "SUBPLAN",
                "CACHED_PLAN", "MATCH", "REPLACE_ROOT", "TEXT_MATCH", "TEXT_OR", "GEO_NEAR_2D",
                "GEO_NEAR_2DSPHERE"));
        KNOWN_STAGES = Set.copyOf(known);
    }

    private MongoShapeGuard() {
    }

    static boolean known(String stage) {
        return KNOWN_STAGES.contains(stage);
    }
}
