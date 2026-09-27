package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoShellParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MongoSimpleAnalyzerTest {

    private final MongoSimpleAnalyzer analyzer = new MongoSimpleAnalyzer(new MongoShellParser());

    @Test
    @DisplayName("a $lookup pipeline with no $match has no filtered field, and never 'from'")
    void lookupOnlyHasNoFilter() {
        QueryAnalysisResult result = analyzer.analyze(
                "db.customers.aggregate([{\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\","
                        + " \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, {\"$unwind\": \"$orders\"}])",
                null);

        assertThat(result.getFilteredColumn()).isNull();
        assertThat(result.isHasUnindexedFilter()).isFalse();
        assertThat(result.getQueryType()).isEqualTo("AGGREGATE");
    }

    @Test
    @DisplayName("a pipeline's first $match names the filtered field")
    void pipelineMatchNamesField() {
        QueryAnalysisResult result = analyzer.analyze(
                "db.customers.aggregate([{\"$match\": {\"city\": \"Pune\"}}, {\"$group\": {\"_id\": \"$city\"}}])",
                null);

        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.isHasUnindexedFilter()).isTrue();
    }

    @Test
    @DisplayName("a projection is not a filter")
    void projectionIsNotAFilter() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({}, {\"name\": 1})", null);

        assertThat(result.getFilteredColumn()).isNull();
        assertThat(result.isHasUnindexedFilter()).isFalse();
        assertThat(result.getQueryType()).isEqualTo("FIND");
    }

    @Test
    @DisplayName("a sort is not a filter")
    void sortIsNotAFilter() {
        QueryAnalysisResult result = analyzer.analyze("db.products.find({}).sort({\"price\": -1})", null);

        assertThat(result.getFilteredColumn()).isNull();
        assertThat(result.isHasUnindexedFilter()).isFalse();
    }

    @Test
    @DisplayName("countDocuments is a COUNT on its filter's field")
    void countDocumentsIsCount() {
        QueryAnalysisResult result = analyzer.analyze("db.orders.countDocuments({\"status\": \"X\"})", null);

        assertThat(result.getQueryType()).isEqualTo("COUNT");
        assertThat(result.getFilteredColumn()).isEqualTo("status");
    }

    @Test
    @DisplayName("deleteMany is a DELETE")
    void deleteManyIsDelete() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.deleteMany({\"city\": \"Pune\"})", null);

        assertThat(result.getQueryType()).isEqualTo("DELETE");
        assertThat(result.getFilteredColumn()).isEqualTo("city");
    }

    @Test
    @DisplayName("an operator-only filter reports no field")
    void operatorOnlyFilterHasNoField() {
        QueryAnalysisResult result = analyzer.analyze(
                "db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}]})", null);

        assertThat(result.getFilteredColumn()).isNull();
    }

    @Test
    @DisplayName("text the parser can't read falls back to the old checks without throwing")
    void unparseableFallsBack() {
        assertThatCode(() -> analyzer.analyze("{ city: \"mumbai\" }", null)).doesNotThrowAnyException();

        QueryAnalysisResult result = analyzer.analyze("{ city: \"mumbai\" }", null);
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getQueryType()).isEqualTo("FIND");
    }

    @Test
    @DisplayName("an unanchored regex is still caught in a parsed query")
    void unanchoredRegexStillCaught() {
        QueryAnalysisResult result = analyzer.analyze(
                "db.customers.aggregate([{\"$match\": {\"city\": {\"$regex\": \"mum\"}}}])", null);

        assertThat(result.isHasUnanchoredRegex()).isTrue();
        assertThat(result.getFilteredColumn()).isEqualTo("city");
    }
}
