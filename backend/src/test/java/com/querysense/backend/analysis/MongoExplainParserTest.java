package com.querysense.backend.analysis;

import com.querysense.backend.dto.PlanNode;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoExplainParserTest {

    private static final long ROWS = MongoExplainFixtureCapture.ROWS;

    private final MongoExplainParser parser = new MongoExplainParser();

    private QueryAnalysisResult read(String fixture) {
        MongoFixtures.Captured captured = MongoFixtures.explain(fixture);
        QueryAnalysisResult result = new QueryAnalysisResult();
        parser.read(captured.explain(), captured.query(), result);
        return result;
    }

    private static TableAnalysis table(QueryAnalysisResult result, String name) {
        return result.getTables().stream().filter(t -> name.equals(t.getTableName())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a collection scan maps to a full scan with documents examined, returned and the share kept")
    void findCollscan() {
        QueryAnalysisResult result = read("find-collscan");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isUsesIndex()).isFalse();
        assertThat(result.getAccessType()).isEqualTo("COLLSCAN");
        assertThat(result.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getEstimatedRowsReturned()).isBetween(1L, ROWS - 1);
        assertThat(result.getQueryCost()).isNull();
        assertThat(result.getExecutionMillis()).isNotNull();
        TableAnalysis customers = table(result, "customers");
        assertThat(customers.isFullTableScan()).isTrue();
        assertThat(customers.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(customers.getFiltered())
                .isEqualTo(100.0 * result.getEstimatedRowsReturned() / ROWS);
        assertThat(result.getPlanTree().kind()).isEqualTo("COLLSCAN");
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("Collection scan").contains("customers"));
    }

    @Test
    @DisplayName("the express index path on a unique field is index use, with its index named")
    void findExpressIxscan() {
        QueryAnalysisResult result = read("find-express-ixscan");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.isHasFullTableScan()).isFalse();
        assertThat(result.getKeyUsed()).isEqualTo("email_1");
        assertThat(result.getAccessType()).isEqualTo("EXPRESS_IXSCAN");
        assertThat(result.getWarnings()).noneSatisfy(w -> assertThat(w).contains("Collection scan"));
    }

    @Test
    @DisplayName("an index scan under FETCH takes its document counts from the FETCH")
    void findFetchIxscan() {
        QueryAnalysisResult result = read("find-fetch-ixscan");

        TableAnalysis customers = table(result, "customers");
        assertThat(customers.getAccessType()).isEqualTo("IXSCAN");
        assertThat(customers.getKeyUsed()).isEqualTo("email_1");
        assertThat(customers.getEstimatedRows()).isPositive();
        assertThat(result.getPlanTree().kind()).isEqualTo("FETCH");
        assertThat(result.getPlanTree().children()).extracting(PlanNode::kind).containsExactly("IXSCAN");
    }

    @Test
    @DisplayName("a blocking SORT is an in-memory sort")
    void findSort() {
        QueryAnalysisResult result = read("find-sort");

        assertThat(result.isUsesFilesort()).isTrue();
        assertThat(result.getPlanTree().kind()).isEqualTo("SORT");
        assertThat(result.getPlanTree().usesFilesort()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("In-memory sort"));
    }

    @Test
    @DisplayName("an _id lookup names the _id_ index even when the stage carries no index name")
    void findIdhack() {
        QueryAnalysisResult result = read("find-idhack");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.getKeyUsed()).isEqualTo("_id_");
    }

    @Test
    @DisplayName("a multikey index on an embedded array path is index use")
    void findMultikey() {
        assertThat(read("find-multikey").getKeyUsed()).isEqualTo("items.product_id_1");
    }

    @Test
    @DisplayName("a collection missing from mongod fails the analysis, naming the collection")
    void missingCollectionFails() {
        MongoFixtures.Captured captured = MongoFixtures.explain("find-missing-collection");
        MongoQuery declared = new MongoShellParser().parse("db.customers.find({\"a\": 1})");
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(captured.explain(), declared, result);

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).singleElement()
                .satisfies(w -> assertThat(w).contains("'customers'").contains("does not exist"));
    }

    @Test
    @DisplayName("a pushed-down $group reads its classic tree, claims no discard, and is not a spill")
    void pushedDownGroupClaimsNoDiscard() {
        QueryAnalysisResult result = read("agg-group");

        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isUsesTemporaryTable()).isFalse();
        TableAnalysis orders = table(result, "orders");
        assertThat(orders.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(orders.getFiltered()).isNull();
        assertThat(result.getFiltered()).isNull();
        assertThat(result.getPlanTree().kind()).isEqualTo("GROUP");
    }

    @Test
    @DisplayName("a $lookup reports the joined collection and its index, and the sort above it")
    void lookupAndSort() {
        QueryAnalysisResult result = read("agg-lookup-sort");

        assertThat(table(result, "orders").isFullTableScan()).isTrue();
        TableAnalysis customers = table(result, "customers");
        assertThat(customers.isUsesIndex()).isTrue();
        assertThat(customers.getKeyUsed()).isEqualTo("_id_");
        assertThat(result.isUsesFilesort()).isTrue();
        assertThat(result.getEstimatedRows()).isEqualTo(2 * ROWS);
    }

    @Test
    @DisplayName("a pipeline MongoDB did not push down reads its $cursor, and later stages sit above it in the tree")
    void cursorPipeline() {
        QueryAnalysisResult result = read("agg-facet");

        assertThat(table(result, "orders").getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getPlanTree().kind()).isEqualTo("$facet");
        assertThat(result.getPlanTree().children()).extracting(PlanNode::kind).containsExactly("COLLSCAN");
    }

    @Test
    @DisplayName("count reports the matching documents, not count's own zero")
    void countReportsMatchesNotZero() {
        QueryAnalysisResult result = read("count");

        assertThat(result.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getEstimatedRowsReturned()).isBetween(1L, ROWS - 1);
        assertThat(table(result, "orders").getFiltered()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("delete is read from its explain, like count")
    void deleteIsRead() {
        QueryAnalysisResult result = read("delete");

        assertThat(result.getPlanTree().kind()).isEqualTo("BATCHED_DELETE");
        assertThat(result.getEstimatedRowsReturned()).isPositive();
    }

    @Test
    @DisplayName("every captured fixture uses only stages the guard knows")
    void fixturesHaveNoUnknownStages() {
        for (String name : MongoExplainFixtureCapture.QUERIES.keySet()) {
            if (name.equals("find-missing-collection")) {
                continue;
            }
            assertThat(read(name).getWarnings()).as(name)
                    .noneSatisfy(w -> assertThat(w).contains("does not read"));
        }
    }

    private static MongoQuery find() {
        return new MongoShellParser().parse("db.customers.find({\"city\": \"Mumbai\"})");
    }

    private static Document stats(Document executionStages) {
        return new Document("nReturned", 5).append("totalDocsExamined", 100)
                .append("executionTimeMillis", 1).append("executionStages", executionStages);
    }

    @Test
    @DisplayName("an unknown stage warns, and the stages it wraps are still read")
    void unknownStageWarns() {
        Document collscan = new Document("stage", "COLLSCAN").append("nss", "db.customers")
                .append("docsExamined", 100).append("nReturned", 5);
        Document explain = new Document("queryPlanner", new Document("winningPlan", new Document("stage", "MYSTERY")
                .append("inputStage", collscan)))
                .append("executionStats", stats(new Document("stage", "MYSTERY").append("inputStage", collscan)));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("MYSTERY").contains("does not read"));
    }

    @Test
    @DisplayName("a group that spilled to disk anywhere in the execution stats is a temporary-table signal")
    void spilledGroup() {
        Document explain = new Document("queryPlanner", new Document("winningPlan", new Document("queryPlan",
                new Document("stage", "GROUP").append("inputStage",
                        new Document("stage", "COLLSCAN").append("nss", "db.customers")))))
                .append("executionStats", stats(new Document("stage", "project").append("inputStage",
                        new Document("stage", "group").append("usedDisk", true))));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(result.isUsesTemporaryTable()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("spilled to disk"));
    }

    @Test
    @DisplayName("an explain result without a winning plan is unreadable, not empty")
    void unreadableShape() {
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(new Document("ok", 1.0), find(), result);

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).singleElement().satisfies(w -> assertThat(w).contains("shape"));
    }

    @Test
    @DisplayName("a missing statistic is named and its figure left out")
    void missingStatistic() {
        Document collscan = new Document("stage", "COLLSCAN").append("nss", "db.customers");
        Document explain = new Document("queryPlanner", new Document("winningPlan", collscan))
                .append("executionStats", new Document("nReturned", 5).append("executionTimeMillis", 1)
                        .append("executionStages", collscan));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(result.getEstimatedRows()).isNull();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("totalDocsExamined"));
    }

    @Test
    @DisplayName("an index a rejected plan would have used is reported as a possible key on the scanned collection")
    void rejectedPlanIndexIsPossibleKey() {
        Document collscan = new Document("stage", "COLLSCAN").append("nss", "db.customers")
                .append("docsExamined", 100).append("nReturned", 5);
        Document explain = new Document("queryPlanner", new Document("winningPlan", collscan)
                .append("rejectedPlans", List.of(new Document("stage", "FETCH").append("inputStage",
                        new Document("stage", "IXSCAN").append("indexName", "city_1")))))
                .append("executionStats", stats(collscan));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(table(result, "customers").getPossibleKeys()).isEqualTo("city_1");
    }
}
