package com.querysense.backend.advisor;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.optimization.IndexStatements;
import com.querysense.backend.schema.IndexSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Schema recommendations for MongoDB: equality → sort → range compound indexes, regex and grouping advice. */
@Component
public class MongoIndexAdvisor implements IndexAdvisor {

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        List<SchemaRecommendation> recommendations = new ArrayList<>();
        MongoQueryShape shape = result.getMongoShape();
        if (shape == null) {
            return recommendations;
        }
        String collection = result.getTableName() != null ? result.getTableName() : shape.collection();

        if (!shape.unanchoredRegexFields().isEmpty()) {
            String field = shape.unanchoredRegexFields().get(0);
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    collection,
                    field,
                    "The search pattern can match anywhere inside the text. The database can only take a "
                            + "shortcut when a pattern is pinned to the start of the value, so here it reads "
                            + "every document whether an index exists or not.",
                    "Unanchored $regex on '" + field + "' — MongoDB cannot use an index for a regex that isn't "
                            + "left-anchored, regardless of whether an index exists on the field.",
                    "Rewrite the regex with a leading '^' if a prefix match is intended, or create a text index "
                            + "(db." + collection + ".createIndex({ \"" + field + "\": \"text\" })) if full "
                            + "substring search is required."));
        }

        boolean scanFixed = false;
        if (result.isHasFullTableScan() && !result.isUsesIndex() && !shape.esrKeys().isEmpty()) {
            List<IndexSpec.KeyPart> keys = shape.esrKeys();
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    collection,
                    columns(keys),
                    "To answer this, the database is reading " + documentsPhrase(result) + " of " + collection
                            + " and checking each one, because no index covers " + columns(keys) + ". An index "
                            + "works like the index at the back of a book: it lets the database jump straight to "
                            + "the matching documents instead of reading everything."
                            + (keys.size() > 1
                                    ? " The fields are ordered exact matches first, then the sort, then any "
                                            + "range — the order the database can make the most use of."
                                    : ""),
                    "Collection scan (COLLSCAN) with no index in use on '" + collection + "', reported by "
                            + "MongoDB's explain. Compound key order follows equality → sort → range, so one "
                            + "index serves the filter and the sort together.",
                    IndexStatements.create(Dialect.MONGODB, collection, keys)));
            scanFixed = true;
        }

        if (!scanFixed && result.isUsesFilesort() && !shape.sortKeys().isEmpty()) {
            List<IndexSpec.KeyPart> keys = shape.sortIndexKeys();
            String sortFields = columns(shape.sortKeys());
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    collection,
                    columns(keys),
                    "The database can find the documents, but then has to put them in order in memory, "
                            + "because no index keeps " + sortFields + " already sorted. On a big collection that "
                            + "ordering step is usually the slow part. An index that starts with the exact-match "
                            + "fields and then the sort fields lets it return them already in order. Re-check "
                            + "after applying rather than assuming it worked.",
                    "Blocking SORT stage in the winning plan for " + sortFields + " — an index on the equality "
                            + "fields followed by the sort fields lets MongoDB walk the index in order instead.",
                    IndexStatements.create(Dialect.MONGODB, collection, keys)));
        }

        if (shape.groupsWithoutIndexedMatch()) {
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    collection,
                    null,
                    "The database is combining documents into groups without first narrowing them down "
                            + "through an index, so every document is read to build the groups. Filtering on an "
                            + "indexed field before the grouping step usually helps. There is no single command "
                            + "that reliably fixes this, so it is flagged for a person to look at.",
                    "$group stage with no $match on an indexed field before it — every document in '"
                            + collection + "' feeds the group.",
                    "Add a $match on an indexed field before the $group in '" + collection
                            + "', or index the field the existing $match filters on."));
        }

        return recommendations;
    }

    private static String columns(List<IndexSpec.KeyPart> keys) {
        return keys.stream().map(IndexSpec.KeyPart::column).collect(Collectors.joining(", "));
    }

    private static String documentsPhrase(QueryAnalysisResult result) {
        Long documents = result.getEstimatedRows();
        if (documents == null || documents <= 0) {
            return "every document";
        }
        return "all " + String.format(Locale.UK, "%,d", documents) + " documents";
    }
}
