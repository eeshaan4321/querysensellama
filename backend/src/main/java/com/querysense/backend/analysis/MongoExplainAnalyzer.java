package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Analyses a MongoDB query: parse, preflight, the real indexes and explain plan, then the verdict. */
@Component
public class MongoExplainAnalyzer implements PlanAnalyzer {

    private static final Pattern COLLECTION_PATTERN = Pattern.compile("\\bdb\\s*\\.\\s*(\\w+)\\s*\\.");

    private final MongoShellParser shellParser;
    private final MongoPreflightValidator preflight;
    private final MongoExplainExecutor executor;
    private final MongoExplainParser explainParser;
    private final VerdictService verdictService;

    public MongoExplainAnalyzer(MongoShellParser shellParser,
                                MongoPreflightValidator preflight,
                                MongoExplainExecutor executor,
                                MongoExplainParser explainParser,
                                VerdictService verdictService) {
        this.shellParser = shellParser;
        this.preflight = preflight;
        this.executor = executor;
        this.explainParser = explainParser;
        this.verdictService = verdictService;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public QueryAnalysisResult analyze(String text) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType(Dialect.MONGODB.label());
        result.setQueryType("FIND");
        result.setTableName(collectionFrom(text));

        MongoQuery query;
        try {
            query = shellParser.parse(text);
        } catch (MongoShellParseException e) {
            return failed(result, "This MongoDB query could not be read: " + e.getMessage());
        }
        result.setTableName(query.collection());
        result.setQueryType(query.operation().name());

        Optional<String> refused = preflight.validate(query);
        if (refused.isPresent()) {
            return failed(result, refused.get());
        }

        try {
            Set<String> leading = leadingKeys(executor.indexes(query.collection()));
            MongoQueryShape shape = MongoQueryFields.shape(query, leading);
            result.setMongoShape(shape);
            flagFilter(shape, leading, result);
            explainParser.read(executor.explain(query), query, result);
        } catch (MongoCommandException e) {
            return failed(result, "MongoDB could not plan this query: " + e.getErrorMessage());
        }

        if (result.isHasUnindexedFilter() && !result.isHasFullTableScan() && !result.isExplainFailed()) {
            result.addWarning("Filtering on '" + result.getFilteredColumn() + "', which no index starts with on '"
                    + query.collection() + "'. MongoDB filters it after reading each document.");
        }
        verdictService.apply(result);
        return result;
    }

    private static void flagFilter(MongoQueryShape shape, Set<String> leading, QueryAnalysisResult result) {
        List<String> filtered = Stream.concat(shape.equalityFields().stream(), shape.rangeFields().stream()).toList();
        Optional<String> unindexed = filtered.stream().filter(f -> !leading.contains(f)).findFirst();
        result.setHasUnindexedFilter(unindexed.isPresent());
        result.setFilteredColumn(unindexed.orElse(filtered.isEmpty() ? null : filtered.get(0)));
        result.setOrderByColumn(shape.sortKeys().isEmpty() ? null : shape.sortKeys().get(0).column());
        if (!shape.unanchoredRegexFields().isEmpty()) {
            result.setHasUnanchoredRegex(true);
            result.addWarning("Unanchored $regex pattern detected — "
                    + "prefix with '^' to allow index usage, if an index exists on this field.");
        }
    }

    private static Set<String> leadingKeys(List<Document> indexes) {
        return indexes.stream()
                .map(i -> i.get("key") instanceof Document key ? key.keySet().stream().findFirst().orElse(null) : null)
                .filter(k -> k != null)
                .collect(Collectors.toSet());
    }

    private QueryAnalysisResult failed(QueryAnalysisResult result, String reason) {
        result.addWarning(reason);
        result.setExplainFailed(true);
        verdictService.apply(result);
        return result;
    }

    private static String collectionFrom(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = COLLECTION_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
