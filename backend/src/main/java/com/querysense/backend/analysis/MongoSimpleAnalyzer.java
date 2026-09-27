package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks MongoDB queries for unindexed filters and unanchored regexes, reading them with MongoShellParser when it can. */
@Component
public class MongoSimpleAnalyzer {

    private static final List<String> KNOWN_INDEXED_FIELDS = List.of("_id", "email", "userId");

    private static final Pattern COLLECTION_PATTERN =
            Pattern.compile("\\bdb\\s*\\.\\s*(\\w+)\\s*\\.");

    private static final Pattern FILTER_FIELD_PATTERN =
            Pattern.compile("\\{\\s*\"?(\\w+)\"?\\s*:");

    private static final Pattern REGEX_VALUE_PATTERN =
            Pattern.compile("\\$regex\"?\\s*:\\s*\"([^\"]*)\"");

    private final MongoShellParser parser;

    public MongoSimpleAnalyzer(MongoShellParser parser) {
        this.parser = parser;
    }

    public QueryAnalysisResult analyze(String mongoQueryText, String collectionName) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName(collectionName != null ? collectionName : collectionFrom(mongoQueryText));
        result.setQueryType("FIND");

        if (mongoQueryText == null || mongoQueryText.isBlank()) {
            result.addWarning("No MongoDB query text was provided to analyze.");
            return result;
        }

        try {
            MongoQuery query = parser.parse(mongoQueryText);
            result.setQueryType(query.operation().name());
            filteredField(query).ifPresent(field -> flagUnindexed(field, result));
        } catch (MongoShellParseException e) {
            checkUnindexedFilter(mongoQueryText, result);
        }
        checkUnanchoredRegex(mongoQueryText, result);

        return result;
    }

    /** db.<collection>.find(...) names its collection; reading it beats reporting 'null'. */
    private String collectionFrom(String queryText) {
        if (queryText == null) {
            return null;
        }
        Matcher matcher = COLLECTION_PATTERN.matcher(queryText);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Optional<String> filteredField(MongoQuery query) {
        Document filter = query.operation() == MongoQuery.Operation.AGGREGATE
                ? firstMatch(query.pipeline())
                : query.filter();
        if (filter == null) {
            return Optional.empty();
        }
        return filter.keySet().stream().filter(k -> !k.startsWith("$")).findFirst();
    }

    private static Document firstMatch(List<Document> pipeline) {
        if (pipeline == null) {
            return null;
        }
        for (Document stage : pipeline) {
            Object match = stage.get("$match");
            if (match instanceof Document document) {
                return document;
            }
        }
        return null;
    }

    private void flagUnindexed(String field, QueryAnalysisResult result) {
        if (KNOWN_INDEXED_FIELDS.contains(field)) {
            return;
        }
        result.setHasUnindexedFilter(true);
        result.setFilteredColumn(field);
        result.addWarning("Filtering on '" + field + "' with no known index — "
                + "this may cause a full collection scan.");
    }

    private void checkUnindexedFilter(String queryText, QueryAnalysisResult result) {
        Matcher matcher = FILTER_FIELD_PATTERN.matcher(queryText);
        while (matcher.find()) {
            String field = matcher.group(1);
            if (!KNOWN_INDEXED_FIELDS.contains(field) && !field.startsWith("$")) {
                flagUnindexed(field, result);
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