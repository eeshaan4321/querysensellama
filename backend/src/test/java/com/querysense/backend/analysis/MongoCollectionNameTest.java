package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoShellParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collection a Mongo query names is recoverable from the query text, so a recommendation
 * about it need not say 'null'.
 */
class MongoCollectionNameTest {

    private final MongoSimpleAnalyzer analyzer = new MongoSimpleAnalyzer(new MongoShellParser());

    @Test
    @DisplayName("the collection is read from db.<name>.find(...)")
    void readsCollectionFromFind() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({ city: \"mumbai\" })", null);

        assertThat(result.getTableName()).isEqualTo("customers");
    }

    @Test
    @DisplayName("an aggregate names its collection too")
    void readsCollectionFromAggregate() {
        QueryAnalysisResult result =
                analyzer.analyze("db.orders.aggregate([{ $match: { status: \"paid\" } }])", null);

        assertThat(result.getTableName()).isEqualTo("orders");
    }

    @Test
    @DisplayName("an underscored collection name survives intact")
    void readsUnderscoredCollection() {
        QueryAnalysisResult result = analyzer.analyze("db.order_items.find({ quantity: 3 })", null);

        assertThat(result.getTableName()).isEqualTo("order_items");
    }

    @Test
    @DisplayName("an explicit collection name wins over the one in the text")
    void explicitNameWins() {
        QueryAnalysisResult result =
                analyzer.analyze("db.customers.find({})", "customers_archive");

        assertThat(result.getTableName()).isEqualTo("customers_archive");
    }

    @Test
    @DisplayName("text that names no collection leaves it null rather than guessing")
    void unrecognisedTextLeavesItNull() {
        QueryAnalysisResult result = analyzer.analyze("{ city: \"mumbai\" }", null);

        assertThat(result.getTableName()).isNull();
    }

    @Test
    @DisplayName("blank input still leaves it null and still warns")
    void blankInputStillWarns() {
        QueryAnalysisResult result = analyzer.analyze("   ", null);

        assertThat(result.getTableName()).isNull();
        assertThat(result.getWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("the existing filter and regex checks are unaffected")
    void existingChecksStillFire() {
        QueryAnalysisResult result =
                analyzer.analyze("db.customers.find({ \"city\": { \"$regex\": \"mum\" } })", null);

        assertThat(result.getTableName()).isEqualTo("customers");
        assertThat(result.isHasUnindexedFilter()).isTrue();
        assertThat(result.isHasUnanchoredRegex()).isTrue();
    }
}
