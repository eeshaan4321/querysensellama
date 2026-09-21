package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text-based checks for MongoDB queries: unindexed filters and unanchored regexes. */
@Component
public class MongoSimpleAnalyzer {

    private static final List<String> KNOWN_INDEXED_FIELDS = List.of("_id", "email", "userId");

    private static final Pattern FILTER_FIELD_PATTERN =
            Pattern.compile("\\{\\s*\"?(\\w+)\"?\\s*:");

    private static final Pattern REGEX_VALUE_PATTERN =
            Pattern.compile("\\$regex\"?\\s*:\\s*\"([^\"]*)\"");

    public QueryAnalysisResult analyze(String mongoQueryText, String collectionName) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName(collectionName);
        result.setQueryType("FIND");

        if (mongoQueryText == null || mongoQueryText.isBlank()) {
            result.addWarning("No MongoDB query text was provided to analyze.");
            return result;
        }

        checkUnindexedFilter(mongoQueryText, result);
        checkUnanchoredRegex(mongoQueryText, result);

        return result;
    }

    private void checkUnindexedFilter(String queryText, QueryAnalysisResult result) {
        Matcher matcher = FILTER_FIELD_PATTERN.matcher(queryText);
        while (matcher.find()) {
            String field = matcher.group(1);
            if (!KNOWN_INDEXED_FIELDS.contains(field) && !field.startsWith("$")) {
                result.setHasUnindexedFilter(true);
                result.setFilteredColumn(field);
                result.addWarning("Filtering on '" + field + "' with no known index — "
                        + "this may cause a full collection scan.");
                break;
            }
        }
    }

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