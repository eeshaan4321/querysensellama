package com.querysense.backend.advisor;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MongoIndexAdvisorTest {

    private final MongoIndexAdvisor advisor = new MongoIndexAdvisor();

    private static QueryAnalysisResult result(MongoQueryShape shape) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName(shape == null ? "orders" : shape.collection());
        result.setEstimatedRows(12_000L);
        result.setMongoShape(shape);
        return result;
    }

    private static MongoQueryShape shape(List<String> eq, List<IndexSpec.KeyPart> sort, List<String> range,
                                         List<String> regex, boolean group) {
        return new MongoQueryShape("orders", eq, sort, range, regex, group);
    }

    private static QueryAnalysisResult scanned(MongoQueryShape shape) {
        QueryAnalysisResult result = result(shape);
        result.setHasFullTableScan(true);
        result.setAccessType("COLLSCAN");
        return result;
    }

    @Test
    @DisplayName("SA1: a scan on one field recommends createIndex on it")
    void sa1Single() {
        List<SchemaRecommendation> recommendations =
                advisor.recommend(scanned(shape(List.of("status"), List.of(), List.of(), List.of(), false)));

        assertThat(recommendations).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("INDEX");
            assertThat(r.getTargetTable()).isEqualTo("orders");
            assertThat(r.getTargetColumn()).isEqualTo("status");
            assertThat(r.getSuggestedAction()).isEqualTo("db.orders.createIndex({ \"status\": 1 });");
            assertThat(r.getSummary()).contains("12,000");
        });
    }

    @Test
    @DisplayName("SA1 with a sort and a range collapses into one compound index, equality → sort → range")
    void sa1Esr() {
        List<SchemaRecommendation> recommendations = advisor.recommend(scanned(shape(List.of("status"),
                List.of(new IndexSpec.KeyPart("order_date", -1)), List.of("employee_id"), List.of(), false)));

        assertThat(recommendations).singleElement().satisfies(r -> assertThat(r.getSuggestedAction())
                .isEqualTo("db.orders.createIndex({ \"status\": 1, \"order_date\": -1, \"employee_id\": 1 });"));
    }

    @Test
    @DisplayName("SA2: an in-memory sort with an index in use recommends equality then sort fields")
    void sa2() {
        QueryAnalysisResult result = result(shape(List.of("customer_id"),
                List.of(new IndexSpec.KeyPart("order_date", -1)), List.of(), List.of(), false));
        result.setUsesIndex(true);
        result.setUsesFilesort(true);

        assertThat(advisor.recommend(result)).singleElement().satisfies(r -> assertThat(r.getSuggestedAction())
                .isEqualTo("db.orders.createIndex({ \"customer_id\": 1, \"order_date\": -1 });"));
    }

    @Test
    @DisplayName("SA3: a $group with no indexed $match before it is flagged for review")
    void sa3() {
        QueryAnalysisResult result = result(shape(List.of(), List.of(), List.of(), List.of(), true));
        result.setUsesIndex(true);

        assertThat(advisor.recommend(result)).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("STRUCTURAL");
            assertThat(r.getSuggestedAction()).doesNotContain("createIndex({ \"");
        });
    }

    @Test
    @DisplayName("an unanchored regex is structural advice naming a text index on its field, not an ordinary index")
    void regex() {
        List<SchemaRecommendation> recommendations =
                advisor.recommend(scanned(shape(List.of(), List.of(), List.of(), List.of("note"), false)));

        assertThat(recommendations).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("STRUCTURAL");
            assertThat(r.getSuggestedAction()).contains("db.orders.createIndex({ \"note\": \"text\" })");
        });
    }

    @Test
    @DisplayName("nothing to index, an index already in use, or no shape: no recommendation")
    void quietCases() {
        assertThat(advisor.recommend(scanned(shape(List.of(), List.of(), List.of(), List.of(), false)))).isEmpty();
        QueryAnalysisResult indexed = result(shape(List.of("status"), List.of(), List.of(), List.of(), false));
        indexed.setUsesIndex(true);
        assertThat(advisor.recommend(indexed)).isEmpty();
        QueryAnalysisResult failed = result(null);
        failed.setHasFullTableScan(true);
        assertThat(advisor.recommend(failed)).isEmpty();
    }

    @Test
    @DisplayName("summaries are plain language: no plan jargon")
    void plainSummaries() {
        List<QueryAnalysisResult> cases = List.of(
                scanned(shape(List.of("status"), List.of(new IndexSpec.KeyPart("order_date", -1)), List.of(), List.of(), false)),
                scanned(shape(List.of(), List.of(), List.of(), List.of("note"), false)),
                result(shape(List.of(), List.of(), List.of(), List.of(), true)));

        for (QueryAnalysisResult result : cases) {
            for (SchemaRecommendation r : advisor.recommend(result)) {
                assertThat(r.getSummary().toLowerCase(Locale.ROOT))
                        .doesNotContain("collscan").doesNotContain("explain").doesNotContain("$regex")
                        .doesNotContain("$group").doesNotContain("filesort");
                assertThat(r.getReason()).isNotBlank().isNotEqualTo(r.getSummary());
            }
        }
    }
}
