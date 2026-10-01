package com.querysense.backend.analysis;

import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.AnalysisVerdict.Severity;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictServiceTest {

    private static final long LARGE = 1000L;

    private final VerdictService service = new VerdictService(LARGE);

    private TableAnalysis table(String name, String accessType, Long rows, Double filtered,
                                String possibleKeys, String keyUsed) {
        TableAnalysis t = new TableAnalysis();
        t.setTableName(name);
        t.setAccessType(accessType);
        t.setEstimatedRows(rows);
        t.setFiltered(filtered);
        t.setPossibleKeys(possibleKeys);
        t.setKeyUsed(keyUsed);
        t.setFullTableScan("ALL".equals(accessType));
        t.setUsesIndex(keyUsed != null);
        return t;
    }

    private QueryAnalysisResult resultWith(TableAnalysis... tables) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MySQL");
        result.setQueryType("SELECT");
        result.setTables(List.of(tables));

        TableAnalysis heaviest = null;
        for (TableAnalysis t : tables) {
            if (t.isFullTableScan()) result.setHasFullTableScan(true);
            if (t.isUsesIndex() && result.getKeyUsed() == null) result.setKeyUsed(t.getKeyUsed());
            if (t.isUsesIndex()) result.setUsesIndex(true);
            if (t.getEstimatedRows() != null
                    && (heaviest == null || t.getEstimatedRows() > heaviest.getEstimatedRows())) {
                heaviest = t;
            }
        }
        if (heaviest != null) {
            result.setEstimatedRows(heaviest.getEstimatedRows());
            result.setFiltered(heaviest.getFiltered());
            result.setEstimatedRowsReturned(heaviest.getEstimatedRowsReturned());
        }
        return result;
    }

    @Test
    @DisplayName("A failed EXPLAIN is UNKNOWN, never a passing grade")
    void failedExplainIsUnknown() {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setExplainFailed(true);

        AnalysisVerdict verdict = service.evaluate(result);

        assertThat(verdict.getSeverity()).isEqualTo(Severity.UNKNOWN);
        assertThat(verdict.getSeverity()).isNotEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("A failed EXPLAIN outranks every other signal on the result")
    void failedExplainWinsOverPopulatedFields() {
        QueryAnalysisResult result = resultWith(table("customers", "ALL", 50_000L, 1.0, null, null));
        result.setExplainFailed(true);

        assertThat(service.evaluate(result).getSeverity()).isEqualTo(Severity.UNKNOWN);
    }

    @Test
    @DisplayName("A plan touching no table reports that, rather than an empty scan")
    void noTablesIsItsOwnVerdict() {
        AnalysisVerdict verdict = service.evaluate(new QueryAnalysisResult());

        assertThat(verdict.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(verdict.getHeadline()).contains("without reading any table");
    }

    @Test
    @DisplayName("A big scan that discards most of what it reads is CRITICAL")
    void wastefulBigScanIsCritical() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("customers", "ALL", 12_000L, 0.5, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(verdict.getHeadline()).contains("customers").contains("12,000").contains("99.5%");
    }

    @Test
    @DisplayName("A filtered percentage rounding to nothing is capped, not stated as 100%")
    void nearZeroFilteredIsCapped() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("events", "ALL", 900_000L, 0.001, null, null)));

        assertThat(verdict.getHeadline()).contains("over 99.9%").doesNotContain("100%");
    }

    @Test
    @DisplayName("On a join, the verdict names the heaviest scanned table")
    void joinPicksTheWorstScan() {
        AnalysisVerdict verdict = service.evaluate(resultWith(
                table("orders", "ref", 40L, 100.0, "idx_customer", "idx_customer"),
                table("customers", "ALL", 80_000L, 2.0, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(verdict.getHeadline()).contains("customers").doesNotContain("orders");
    }

    @Test
    @DisplayName("Without `filtered`, a big scan is reported without a waste claim")
    void missingFilteredDowngradesAndStaysSilentOnWaste() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("customers", "ALL", 12_000L, null, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(verdict.getHeadline()).contains("12,000").doesNotContain("discards");
    }

    @Test
    @DisplayName("An index MySQL listed and did not use is a WARNING in its own right")
    void ignoredIndexIsAWarning() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("orders", "index", 500L, 30.0, "idx_status", null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(verdict.getHeadline()).contains("orders").contains("chose not to use it");
    }

    @Test
    @DisplayName("No candidate indexes is not the same finding as an ignored one")
    void noCandidatesIsNotAnIgnoredIndex() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("orders", "index", 500L, 30.0, null, null)));

        assertThat(verdict.getHeadline()).doesNotContain("chose not to use it");
    }

    @Test
    @DisplayName("When a scan owns the headline, the ignored index still gets said in the detail")
    void ignoredIndexSurvivesAsDetail() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("customers", "ALL", 12_000L, 3.0, "idx_city", null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(verdict.getDetail()).contains("idx_city").contains("customers");
    }

    @Test
    @DisplayName("A filesort over many rows is a WARNING when nothing worse applies")
    void filesortOnLargeResultIsAWarning() {
        QueryAnalysisResult result = resultWith(
                table("orders", "ref", 20_000L, 100.0, "idx_customer", "idx_customer"));
        result.setUsesFilesort(true);

        AnalysisVerdict verdict = service.evaluate(result);

        assertThat(verdict.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(verdict.getHeadline()).contains("sort").contains("20,000");
    }

    @Test
    @DisplayName("A filesort over few rows is not a finding")
    void filesortOnSmallResultIsNotAWarning() {
        QueryAnalysisResult result = resultWith(
                table("orders", "ref", 40L, 100.0, "idx_customer", "idx_customer"));
        result.setUsesFilesort(true);

        assertThat(service.evaluate(result).getSeverity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("A big scan that returns what it reads is not a fault")
    void bigScanReturningEverythingIsInfo() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("customers", "ALL", 12_000L, 100.0, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(verdict.getHeadline()).contains("returns most of them");
    }

    @Test
    @DisplayName("A scan of a small table is normal, however little it returns")
    void smallScanIsInfo() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("customers", "ALL", 80L, 1.0, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(verdict.getHeadline()).contains("all 80 rows");
    }

    @Test
    @DisplayName("The large-table threshold is the configured value, not a constant")
    void thresholdIsConfigurable() {
        VerdictService strict = new VerdictService(50L);

        AnalysisVerdict verdict = strict.evaluate(
                resultWith(table("customers", "ALL", 80L, 1.0, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("Indexed access with nothing else wrong names the index it used")
    void indexedAccessIsInfo() {
        AnalysisVerdict verdict = service.evaluate(
                resultWith(table("customers", "ref", 12L, 100.0, "idx_city", "idx_city")));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(verdict.getHeadline()).contains("idx_city");
    }

    @Test
    @DisplayName("No headline asserts that adding an index would fix the query")
    void headlinesDoNotPrescribe() {
        List<QueryAnalysisResult> cases = List.of(
                resultWith(table("customers", "ALL", 12_000L, 0.5, null, null)),
                resultWith(table("customers", "ALL", 12_000L, null, null, null)),
                resultWith(table("orders", "index", 5_000L, 30.0, "idx_status", null)),
                resultWith(table("customers", "ALL", 80L, 1.0, null, null)));

        for (QueryAnalysisResult result : cases) {
            String headline = service.evaluate(result).getHeadline();
            assertThat(headline.toLowerCase())
                    .doesNotContain("you should")
                    .doesNotContain("add an index")
                    .doesNotContain("create index");
        }
    }

    @Test
    @DisplayName("No headline uses EXPLAIN vocabulary the banner exists to replace")
    void headlinesAvoidExplainJargon() {
        List<QueryAnalysisResult> cases = List.of(
                resultWith(table("customers", "ALL", 12_000L, 0.5, null, null)),
                resultWith(table("customers", "ALL", 12_000L, 100.0, null, null)),
                resultWith(table("customers", "ref", 12L, 100.0, "idx_city", "idx_city")));

        for (QueryAnalysisResult result : cases) {
            String headline = service.evaluate(result).getHeadline().toLowerCase();
            assertThat(headline)
                    .doesNotContain("access type")
                    .doesNotContain("filesort")
                    .doesNotContain("eq_ref")
                    .doesNotContain("possible_keys");
        }
    }

    private TableAnalysis mongoTable(String name, String stage, Long docs, Double filtered,
                                     String possibleKeys, String keyUsed) {
        TableAnalysis t = new TableAnalysis();
        t.setTableName(name);
        t.setAccessType(stage);
        t.setEstimatedRows(docs);
        t.setFiltered(filtered);
        t.setPossibleKeys(possibleKeys);
        t.setKeyUsed(keyUsed);
        t.setFullTableScan("COLLSCAN".equals(stage));
        t.setUsesIndex(keyUsed != null);
        return t;
    }

    private QueryAnalysisResult mongoResultWith(TableAnalysis... tables) {
        QueryAnalysisResult result = resultWith(tables);
        result.setDatabaseType("MongoDB");
        result.setQueryType("FIND");
        return result;
    }

    @Test
    @DisplayName("MySQL wording is unchanged, character for character")
    void mysqlWordingUnchanged() {
        QueryAnalysisResult failed = resultWith();
        failed.setExplainFailed(true);
        assertThat(service.evaluate(failed).getHeadline())
                .isEqualTo("MySQL couldn't produce an execution plan for this query.");

        assertThat(service.evaluate(resultWith(table("orders", "ALL", 50_000L, 10.0, null, null))).getHeadline())
                .isEqualTo("Reads the whole orders table — about 50,000 rows — then discards 90% of what it read.");

        QueryAnalysisResult sorted = resultWith(table("orders", "range", 5_000L, 30.0, null, "idx_status"));
        sorted.setUsesFilesort(true);
        AnalysisVerdict sortVerdict = service.evaluate(sorted);
        assertThat(sortVerdict.getHeadline()).isEqualTo("MySQL has to sort roughly 5,000 rows after reading them.");
        assertThat(sortVerdict.getDetail()).isEqualTo("No index supplied the rows in the order this query needs, "
                + "so the ordering or grouping work happens as a separate step.");

        AnalysisVerdict indexed = service.evaluate(resultWith(table("customers", "ref", 12L, 100.0, "idx_city", "idx_city")));
        assertThat(indexed.getHeadline()).isEqualTo("Uses the idx_city index to find rows instead of reading the whole table.");
        assertThat(indexed.getDetail()).isEqualTo("About 12 rows are expected to be read.");

        AnalysisVerdict ignored = service.evaluate(resultWith(table("orders", "index", 500L, 30.0, "idx_status", null)));
        assertThat(ignored.getDetail()).isEqualTo("MySQL only uses an index when it estimates that doing so is "
                + "cheaper. On a small table, or for a query that matches most rows, reading the table directly "
                + "genuinely is cheaper.");
    }

    @Test
    @DisplayName("MongoDB verdicts speak of collections, documents and MongoDB")
    void mongoWording() {
        QueryAnalysisResult failed = mongoResultWith();
        failed.setExplainFailed(true);
        assertThat(service.evaluate(failed).getHeadline())
                .isEqualTo("MongoDB couldn't produce an execution plan for this query.");

        AnalysisVerdict wasteful = service.evaluate(mongoResultWith(mongoTable("customers", "COLLSCAN", 2_000L, 10.0, null, null)));
        assertThat(wasteful.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(wasteful.getHeadline()).isEqualTo(
                "Reads the whole customers collection — about 2,000 documents — then discards 90% of what it read.");
        assertThat(wasteful.getDetail()).isEqualTo("MongoDB reported no index it could have used for this collection.");

        AnalysisVerdict indexed = service.evaluate(mongoResultWith(mongoTable("customers", "EXPRESS_IXSCAN", 12L, 100.0, null, "email_1")));
        assertThat(indexed.getHeadline())
                .isEqualTo("Uses the email_1 index to find documents instead of reading the whole collection.");
        assertThat(indexed.getDetail()).isEqualTo("About 12 documents were examined.");

        AnalysisVerdict ignored = service.evaluate(mongoResultWith(mongoTable("orders", "FETCH", 500L, 30.0, "status_1", null)));
        assertThat(ignored.getHeadline()).isEqualTo("An index on orders was available, but MongoDB chose not to use it.");
    }

    @Test
    @DisplayName("MongoDB's in-memory sort and spilled group read as such")
    void mongoSortAndGroup() {
        QueryAnalysisResult sorted = mongoResultWith(mongoTable("customers", "IXSCAN", 5_000L, 30.0, null, "city_1"));
        sorted.setUsesFilesort(true);
        assertThat(service.evaluate(sorted).getHeadline())
                .isEqualTo("MongoDB has to sort roughly 5,000 documents in memory after reading them.");

        QueryAnalysisResult grouped = mongoResultWith(mongoTable("orders", "IXSCAN", 5_000L, 30.0, null, "status_1"));
        grouped.setUsesTemporaryTable(true);
        AnalysisVerdict groupVerdict = service.evaluate(grouped);
        assertThat(groupVerdict.getHeadline())
                .isEqualTo("MongoDB has to group roughly 5,000 documents, and the grouping spilled to disk.");
        assertThat(groupVerdict.getDetail()).isEqualTo("No index supplied the documents in the order this query "
                + "needs, so the sorting or grouping work happens as a separate step.");
    }

    @Test
    @DisplayName("a Mongo scan with no discard figure (count, delete, a pushed-down group) is not graded as waste")
    void mongoScanWithoutDiscardFigureIsNotCritical() {
        AnalysisVerdict verdict = service.evaluate(mongoResultWith(mongoTable("orders", "COLLSCAN", 2_000L, null, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(verdict.getHeadline()).isEqualTo("Reads the whole orders collection — about 2,000 documents.");
    }

    @Test
    @DisplayName("no MongoDB verdict says table, rows or MySQL")
    void mongoNeverSaysSqlWords() {
        QueryAnalysisResult sorted = mongoResultWith(mongoTable("customers", "IXSCAN", 5_000L, 30.0, null, "city_1"));
        sorted.setUsesFilesort(true);
        List<QueryAnalysisResult> cases = List.of(
                mongoResultWith(),
                mongoResultWith(mongoTable("customers", "COLLSCAN", 2_000L, 10.0, null, null)),
                mongoResultWith(mongoTable("customers", "COLLSCAN", 2_000L, 95.0, null, null)),
                mongoResultWith(mongoTable("customers", "COLLSCAN", 80L, 10.0, null, null)),
                mongoResultWith(mongoTable("orders", "FETCH", 500L, 30.0, "status_1", null)),
                mongoResultWith(mongoTable("customers", "IXSCAN", 12L, 100.0, null, "email_1")),
                sorted);

        for (QueryAnalysisResult result : cases) {
            AnalysisVerdict verdict = service.evaluate(result);
            String words = (verdict.getHeadline() + " " + (verdict.getDetail() == null ? "" : verdict.getDetail()))
                    .toLowerCase();
            assertThat(words).as(verdict.getHeadline())
                    .doesNotContain(" table").doesNotContain(" rows").doesNotContain("mysql");
        }
    }
}
