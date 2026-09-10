package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MongoSimpleAnalyzer
 *
 * WHY THIS STAYS DELIBERATELY SIMPLE:
 * The synopsis scopes MongoDB analysis to "simple performance
 * recommendations," not a full execution-plan analyzer like the MySQL
 * side. Paper #13 in the lit review (MongoDB Aggregation Pipeline
 * Performance, 2026) documents that MongoDB's own plan-selection
 * mechanism (FPTP) behaves inconsistently across versions — so rather
 * than build something that tries to mirror MySqlExplainAnalyzer's
 * depth, this class does known-pattern checks that are cheap to reason
 * about and don't depend on trusting MongoDB's internal optimizer.
 *
 * WHY THIS WORKS OFF A STRING (not a real Mongo query object):
 * There is no MongoDB client/query-builder wired up yet (that's
 * Vedant's task). Today, MongoDB-dialect requests just produce
 * LLM-generated query text. This class does regex-based pattern
 * checks against that text. When a real Mongo query object exists,
 * this class's checks can be reimplemented against the object directly —
 * but the QueryAnalysisResult output contract won't need to change,
 * so nothing downstream (Domain Optimization, Schema Advisor) needs
 * to know which implementation is running underneath.
 *
 * KNOWN LIMITATIONS (acceptable for MVP scope):
 * - "Known indexed fields" is a hardcoded placeholder list for now.
 *   Once real collections/indexes exist, this should read from actual
 *   MongoDB index metadata instead.
 * - This does not parse full aggregation pipelines, only simple
 *   filter-style queries — consistent with "simple analysis" scope.
 */
@Component
public class MongoSimpleAnalyzer {

    // Placeholder — WHY hardcoded: no real schema/index metadata exists yet.
    // Vedant's Schema Advisor or MongoDB query builder work should eventually
    // replace this with a real lookup against the collection's actual indexes.
    private static final List<String> KNOWN_INDEXED_FIELDS = List.of("_id", "email", "userId");

    private static final Pattern FILTER_FIELD_PATTERN =
            Pattern.compile("\\{\\s*\"?(\\w+)\"?\\s*:");

    private static final Pattern REGEX_VALUE_PATTERN =
            Pattern.compile("\\$regex\"?\\s*:\\s*\"([^\"]*)\"");

    public QueryAnalysisResult analyze(String mongoQueryText, String collectionName) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName(collectionName);
        result.setQueryType("FIND"); // MVP scope: simple find-style queries only

        if (mongoQueryText == null || mongoQueryText.isBlank()) {
            result.addWarning("No MongoDB query text was provided to analyze.");
            return result;
        }

        checkUnindexedFilter(mongoQueryText, result);
        checkUnanchoredRegex(mongoQueryText, result);

        return result;
    }

    /**
     * WHY THIS CHECK MATTERS:
     * An unindexed filter field forces MongoDB to do a full collection
     * scan — the Mongo equivalent of MySQL's "type: ALL" that
     * MySqlExplainAnalyzer flags. Since we can't run a real explain()
     * yet, this is the best proxy available: does the filtered field
     * appear in our known-indexed list at all.
     */
    private void checkUnindexedFilter(String queryText, QueryAnalysisResult result) {
        Matcher matcher = FILTER_FIELD_PATTERN.matcher(queryText);
        while (matcher.find()) {
            String field = matcher.group(1);
            if (!KNOWN_INDEXED_FIELDS.contains(field) && !field.startsWith("$")) {
                result.setHasUnindexedFilter(true);
                result.setFilteredColumn(field);
                result.addWarning("Filtering on '" + field + "' with no known index — "
                        + "this may cause a full collection scan.");
                break; // one clear warning is enough for MVP scope
            }
        }
    }

    /**
     * WHY THIS CHECK MATTERS:
     * A $regex without a leading ^ anchor cannot use an index even if one
     * exists on that field — MongoDB has to check every document. This is
     * a well-documented, version-independent MongoDB performance pitfall,
     * making it a safe rule-based check (unlike relying on FPTP behavior,
     * which Paper #13 shows is inconsistent across versions).
     */
    private void checkUnanchoredRegex(String queryText, QueryAnalysisResult result) {
        Matcher matcher = REGEX_VALUE_PATTERN.matcher(queryText);
        if (matcher.find()) {
            String pattern = matcher.group(1);
            if (!pattern.startsWith("^")) {
                result.setHasUnanchoredRegex(true);
                result.addWarning("Unanchored $regex pattern detected — "
                        + "prefix with '^' to allow index usage, if an index exists on this field.");
            }
        }
    }
}