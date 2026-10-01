package com.querysense.backend.service.impl;

import com.querysense.backend.advisor.MongoIndexAdvisor;
import com.querysense.backend.advisor.MySqlIndexAdvisor;
import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaAdvisorServiceImplTest {

    private SchemaAdvisorServiceImpl advisor;

    @BeforeEach
    void setUp() {
        advisor = new SchemaAdvisorServiceImpl(List.of(new MySqlIndexAdvisor(), new MongoIndexAdvisor()));
    }

    @Test
    @DisplayName("a full scan with no index in use recommends an index on the filtered column")
    void recommendsIndexForFullScan() {
        List<SchemaRecommendation> result = advisor.recommend(fullScan());

        assertThat(result).hasSize(1);
        SchemaRecommendation r = result.get(0);
        assertThat(r.getType()).isEqualTo("INDEX");
        assertThat(r.getTargetTable()).isEqualTo("user_details");
        assertThat(r.getTargetColumn()).isEqualTo("status");
        assertThat(r.getSuggestedAction())
                .isEqualTo("CREATE INDEX idx_user_details_status ON user_details(status);");
    }

    @Test
    @DisplayName("a full scan is not flagged when an index is already doing that job")
    void noIndexRecommendationWhenIndexAlreadyUsed() {
        QueryAnalysisResult result = fullScan();
        result.setUsesIndex(true);

        assertThat(advisor.recommend(result)).isEmpty();
    }

    @Test
    @DisplayName("a filesort is flagged even when the WHERE clause already uses an index")
    void filesortFlaggedIndependentlyOfIndexUse() {
        QueryAnalysisResult result = base();
        result.setUsesIndex(true);
        result.setUsesFilesort(true);
        result.setOrderByColumn("status");

        List<SchemaRecommendation> recommendations = advisor.recommend(result);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getTargetColumn()).isEqualTo("status");
    }

    @Test
    @DisplayName("a temporary table is flagged for review rather than given a CREATE INDEX")
    void temporaryTableIsStructural() {
        QueryAnalysisResult result = base();
        result.setUsesTemporaryTable(true);

        List<SchemaRecommendation> recommendations = advisor.recommend(result);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getType()).isEqualTo("STRUCTURAL");
        assertThat(recommendations.get(0).getSuggestedAction()).doesNotStartWith("CREATE INDEX");
    }

    @Test
    @DisplayName("a clean query produces no recommendations at all")
    void cleanQueryProducesNothing() {
        assertThat(advisor.recommend(base())).isEmpty();
    }

    @Test
    @DisplayName("a scanned Mongo filter recommends createIndex on the field")
    void recommendsMongoIndex() {
        List<SchemaRecommendation> recommendations = advisor.recommend(mongoScan());

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getSuggestedAction()).contains("createIndex");
    }

    @Test
    @DisplayName("a rule-engine $lookup pipeline with no filter gets no createIndex recommendation")
    void lookupOnlyPipelineGetsNoIndex() {
        QueryAnalysisResult analysed = base();
        analysed.setDatabaseType("MongoDB");
        analysed.setTableName("customers");
        analysed.setQueryType("AGGREGATE");

        assertThat(advisor.recommend(analysed)).isEmpty();
    }

    @Test
    @DisplayName("an unanchored Mongo regex is flagged as structural")
    void recommendsAnchoringRegex() {
        List<SchemaRecommendation> recommendations = advisor.recommend(mongoRegex());

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getType()).isEqualTo("STRUCTURAL");
    }

    @Test
    @DisplayName("every recommendation carries a plain-language summary as well as the technical reason")
    void everyRecommendationHasBothRegisters() {
        for (QueryAnalysisResult result : everyRuleFiring()) {
            for (SchemaRecommendation r : advisor.recommend(result)) {
                assertThat(r.getSummary()).as("summary for %s", r.getType()).isNotBlank();
                assertThat(r.getReason()).as("reason for %s", r.getType()).isNotBlank();
                assertThat(r.getSummary()).isNotEqualTo(r.getReason());
            }
        }
    }

    @Test
    @DisplayName("summaries carry no database jargon and no teammate names")
    void jargonFreeSummaries() {
        List<String> banned = List.of(
                "accesstype", "explain", "filesort", "anant", "vedant", "eeshaan",
                "querysense_uploads", "$regex", "group by", "distinct");

        for (QueryAnalysisResult result : everyRuleFiring()) {
            for (SchemaRecommendation r : advisor.recommend(result)) {
                String summary = r.getSummary().toLowerCase(Locale.ROOT);
                for (String term : banned) {
                    assertThat(summary)
                            .as("summary for %s should not say '%s': %s", r.getType(), term, r.getSummary())
                            .doesNotContain(term);
                }
            }
        }
    }

    @Test
    @DisplayName("no recommendation text names a team member, in either register")
    void noTeammateNamesAnywhere() {
        for (QueryAnalysisResult result : everyRuleFiring()) {
            for (SchemaRecommendation r : advisor.recommend(result)) {
                String all = (r.getSummary() + " " + r.getReason() + " " + r.getSuggestedAction())
                        .toLowerCase(Locale.ROOT);
                assertThat(all).doesNotContain("anant").doesNotContain("vedant").doesNotContain("eeshaan");
            }
        }
    }

    @Test
    @DisplayName("a known row count appears in the summary, so the reader sees the scale")
    void summaryNamesTheRowCount() {
        QueryAnalysisResult result = fullScan();
        result.setEstimatedRows(12000L);

        assertThat(advisor.recommend(result).get(0).getSummary()).contains("12,000");
    }

    @Test
    @DisplayName("an unknown row count degrades to wording that does not leave a gap")
    void summaryCopesWithNullRowCount() {
        QueryAnalysisResult result = fullScan();
        result.setEstimatedRows(null);

        String summary = advisor.recommend(result).get(0).getSummary();
        assertThat(summary).contains("every row").doesNotContain("null");
    }

    private List<QueryAnalysisResult> everyRuleFiring() {
        QueryAnalysisResult filesort = base();
        filesort.setUsesFilesort(true);
        filesort.setOrderByColumn("status");

        QueryAnalysisResult temp = base();
        temp.setUsesTemporaryTable(true);

        return List.of(fullScan(), filesort, temp, mongoScan(), mongoRegex());
    }

    private QueryAnalysisResult fullScan() {
        QueryAnalysisResult result = base();
        result.setHasFullTableScan(true);
        result.setAccessType("ALL");
        result.setFilteredColumn("status");
        result.setEstimatedRows(100L);
        return result;
    }

    private QueryAnalysisResult mongoScan() {
        QueryAnalysisResult result = base();
        result.setDatabaseType("MongoDB");
        result.setHasFullTableScan(true);
        result.setAccessType("COLLSCAN");
        result.setMongoShape(new MongoQueryShape("user_details", List.of("status"), List.of(), List.of(), List.of(), false));
        return result;
    }

    private QueryAnalysisResult mongoRegex() {
        QueryAnalysisResult result = base();
        result.setDatabaseType("MongoDB");
        result.setUsesIndex(true);
        result.setHasUnanchoredRegex(true);
        result.setMongoShape(new MongoQueryShape("user_details", List.of(), List.of(), List.of(), List.of("status"), false));
        return result;
    }

    private QueryAnalysisResult base() {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setTableName("user_details");
        result.setQueryType("SELECT");
        result.setDatabaseType("MySQL");
        return result;
    }

    @Test
    @DisplayName("a filter and a sort on different columns collapse into one composite index")
    void filterAndSortCollapseIntoOneComposite() {
        QueryAnalysisResult result = base();
        result.setHasFullTableScan(true);
        result.setAccessType("ALL");
        result.setFilteredColumn("gender");
        result.setUsesFilesort(true);
        result.setOrderByColumn("status");
        result.setEstimatedRows(12000L);

        List<SchemaRecommendation> recommendations = advisor.recommend(result);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getSuggestedAction())
                .isEqualTo("CREATE INDEX idx_user_details_gender_status "
                        + "ON user_details(gender, status);");
        assertThat(recommendations.get(0).getReason()).contains("one index per table");
    }

    @Test
    @DisplayName("a filter and a sort on the same column do not emit the same statement twice")
    void sameColumnIsNotRecommendedTwice() {
        QueryAnalysisResult result = base();
        result.setHasFullTableScan(true);
        result.setAccessType("ALL");
        result.setFilteredColumn("status");
        result.setUsesFilesort(true);
        result.setOrderByColumn("status");

        List<SchemaRecommendation> recommendations = advisor.recommend(result);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getSuggestedAction())
                .isEqualTo("CREATE INDEX idx_user_details_status ON user_details(status);");
    }

    @Test
    @DisplayName("reconciling the two index rules leaves the structural flag standing")
    void structuralRecommendationSurvivesReconciliation() {
        QueryAnalysisResult result = base();
        result.setHasFullTableScan(true);
        result.setAccessType("ALL");
        result.setFilteredColumn("gender");
        result.setUsesFilesort(true);
        result.setOrderByColumn("status");
        result.setUsesTemporaryTable(true);

        List<SchemaRecommendation> recommendations = advisor.recommend(result);

        assertThat(recommendations).hasSize(2);
        assertThat(recommendations).extracting(SchemaRecommendation::getType)
                .containsExactly("INDEX", "STRUCTURAL");
    }

    @Test
    @DisplayName("no two recommendations ever propose an index on the same table")
    void neverTwoIndexesOnOneTable() {
        for (QueryAnalysisResult result : everyRuleFiring()) {
            List<String> indexedTables = advisor.recommend(result).stream()
                    .filter(r -> "INDEX".equals(r.getType()))
                    .map(SchemaRecommendation::getTargetTable)
                    .toList();
            assertThat(indexedTables).doesNotHaveDuplicates();
        }
    }
}
