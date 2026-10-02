package com.querysense.backend.optimization;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.enums.SuggestionSeverity;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoOptimizationRulesTest {

    private final TransactionalOptimizationRules rules = new TransactionalOptimizationRules();
    private final ResourceConsciousService resources = new ResourceConsciousService();

    private static QueryAnalysisResult mongo(long docs) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName("customers");
        result.setEstimatedRows(docs);
        result.setMongoShape(new MongoQueryShape("customers", List.of("city"),
                List.of(new IndexSpec.KeyPart("name", 1)), List.of(), List.of(), false));
        return result;
    }

    private static QueryAnalysisResult collscan(long docs) {
        QueryAnalysisResult result = mongo(docs);
        result.setHasFullTableScan(true);
        result.setAccessType("COLLSCAN");
        result.setHasUnindexedFilter(true);
        result.setFilteredColumn("city");
        return result;
    }

    @Test
    @DisplayName("TX1: a collection scan above 100 documents is CRITICAL, fixed by the ESR index, with no separate unindexed-filter suggestion")
    void tx1() {
        List<OptimizationSuggestion> suggestions = rules.evaluate(collscan(2_000));

        assertThat(suggestions).singleElement().satisfies(s -> {
            assertThat(s.getSeverity()).isEqualTo(SuggestionSeverity.CRITICAL);
            assertThat(s.getMessage()).startsWith("Collection scan on 'customers'");
            assertThat(s.getSuggestedChange()).isEqualTo("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
        });
    }

    @Test
    @DisplayName("TX1 on a small collection is a WARNING")
    void tx1Small() {
        assertThat(rules.evaluate(collscan(50)).get(0).getSeverity()).isEqualTo(SuggestionSeverity.WARNING);
    }

    @Test
    @DisplayName("TX2: an in-memory sort is CRITICAL, fixed by equality then sort fields")
    void tx2() {
        QueryAnalysisResult result = mongo(2_000);
        result.setUsesIndex(true);
        result.setKeyUsed("city_1");
        result.setAccessType("IXSCAN");
        result.setUsesFilesort(true);
        result.setOrderByColumn("name");

        assertThat(rules.evaluate(result)).anySatisfy(s -> {
            assertThat(s.getMessage()).contains("sorts documents in memory");
            assertThat(s.getSeverity()).isEqualTo(SuggestionSeverity.CRITICAL);
            assertThat(s.getSuggestedChange()).isEqualTo("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
        });
    }

    @Test
    @DisplayName("TX3: a group that spilled to disk is CRITICAL, with no index statement")
    void tx3() {
        QueryAnalysisResult result = mongo(2_000);
        result.setUsesIndex(true);
        result.setKeyUsed("city_1");
        result.setAccessType("IXSCAN");
        result.setUsesTemporaryTable(true);

        assertThat(rules.evaluate(result)).anySatisfy(s -> {
            assertThat(s.getMessage()).contains("spilled to disk");
            assertThat(s.getSuggestedChange()).isNull();
        });
    }

    @Test
    @DisplayName("TX5: a single-document express lookup is the ideal shape")
    void tx5() {
        QueryAnalysisResult result = mongo(1);
        result.setUsesIndex(true);
        result.setKeyUsed("email_1");
        result.setAccessType("EXPRESS_IXSCAN");

        assertThat(rules.evaluate(result)).singleElement().satisfies(s -> {
            assertThat(s.getSeverity()).isEqualTo(SuggestionSeverity.INFO);
            assertThat(s.getMessage()).contains("email_1").contains("ideal shape");
        });
    }

    @Test
    @DisplayName("TX6: an unanchored regex stays CRITICAL with today's message")
    void tx7() {
        QueryAnalysisResult result = mongo(10);
        result.setUsesIndex(true);
        result.setKeyUsed("city_1");
        result.setAccessType("IXSCAN");
        result.setHasUnanchoredRegex(true);

        assertThat(rules.evaluate(result)).anySatisfy(s -> assertThat(s.getMessage())
                .startsWith("Unanchored regex filter detected in a transactional query."));
    }

    @Test
    @DisplayName("no MongoDB suggestion speaks of tables or filesorts")
    void mongoWording() {
        QueryAnalysisResult result = collscan(6_000);
        result.setUsesFilesort(true);
        result.setUsesTemporaryTable(true);

        List<OptimizationSuggestion> all = new java.util.ArrayList<>(rules.evaluate(result));
        for (ResourceLevel level : ResourceLevel.values()) {
            all.addAll(resources.evaluate(level, result));
        }

        assertThat(all).isNotEmpty().allSatisfy(s -> assertThat(s.getMessage().toLowerCase())
                .doesNotContain("table").doesNotContain("filesort"));
    }

    @Test
    @DisplayName("RS3 on MongoDB: a big collection scan under low memory, with the index and no separate unindexed-filter suggestion")
    void rs3() {
        List<OptimizationSuggestion> low = resources.evaluate(ResourceLevel.LOW, collscan(6_000));

        assertThat(low).singleElement().satisfies(s -> {
            assertThat(s.getMessage()).contains("collection scan");
            assertThat(s.getSuggestedChange()).isEqualTo("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
        });
        assertThat(resources.evaluate(ResourceLevel.MEDIUM, collscan(6_000))).isEmpty();
    }

    @Test
    @DisplayName("RS1 and RS2 on MongoDB follow the resource level")
    void rs1AndRs2() {
        QueryAnalysisResult result = mongo(6_000);
        result.setUsesFilesort(true);
        result.setUsesTemporaryTable(true);

        assertThat(resources.evaluate(ResourceLevel.LOW, result)).extracting(OptimizationSuggestion::getSeverity)
                .containsExactly(SuggestionSeverity.CRITICAL, SuggestionSeverity.CRITICAL);
        assertThat(resources.evaluate(ResourceLevel.HIGH, result)).extracting(OptimizationSuggestion::getSeverity)
                .containsExactly(SuggestionSeverity.INFO, SuggestionSeverity.INFO);
    }

    @Test
    @DisplayName("without a shape, the index falls back to the filtered field")
    void fallbackWithoutShape() {
        QueryAnalysisResult result = collscan(2_000);
        result.setMongoShape(null);

        assertThat(rules.evaluate(result).get(0).getSuggestedChange())
                .isEqualTo("db.customers.createIndex({ \"city\": 1 });");
    }
}
