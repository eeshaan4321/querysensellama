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
}
