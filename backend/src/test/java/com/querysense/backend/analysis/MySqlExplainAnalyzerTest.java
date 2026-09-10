package com.querysense.backend.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.querysense.backend.schema.ActiveSchemaProvider;

import java.util.function.Function;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySqlExplainAnalyzerTest
 *
 * WHY THESE TESTS EXIST:
 * The defect this class was rewritten to fix is invisible on a single-table
 * query and only appears on a JOIN — the second EXPLAIN row overwriting the
 * first one's accessType and keyUsed. That is precisely the case a live
 * manual check is least likely to catch, because the response still looks
 * plausible: one table, one access type, no error. So the JOIN cases here
 * are the point of the file, and the single-table cases exist to prove the
 * rewrite didn't cost anything that already worked.
 *
 * WHY JdbcTemplate IS MOCKED RATHER THAN POINTED AT A DATABASE:
 * These need to run on a machine with no MySQL configured — which, per this
 * project's own notes, is a real state for at least one team machine. But
 * the mock deliberately stops short of stubbing out the mapping itself: it
 * captures the real RowMapper and feeds it a fake ResultSet, so the column
 * reads (`possible_keys`, `key_len`, `ref`, NULL `rows`) are genuinely
 * exercised. Stubbing the mapper away would test only the code that was
 * never broken.
 */
class MySqlExplainAnalyzerTest {

    private JdbcTemplate jdbcTemplate;
    private MySqlExplainAnalyzer analyzer;

    /**
     * An ExplainExecutor that hands the work straight to this test's mock
     * JdbcTemplate instead of borrowing a real connection.
     *
     * WHY: every assertion in this class verifies the SQL string passed to
     * jdbcTemplate. Routing through the real executor would need a mock
     * Connection as well, and would re-test catalog switching that
     * ExplainExecutorTest already covers directly — including the guarantee
     * that the built-in schema never switches catalog at all. This keeps
     * the analyzer's own tests about the analyzer.
     */
    private static ExplainExecutor passThrough(JdbcTemplate jdbcTemplate) {
        return new ExplainExecutor(jdbcTemplate, new ActiveSchemaProvider()) {
            @Override
            public <T> T withActiveCatalog(Function<JdbcTemplate, T> work) {
                return work.apply(jdbcTemplate);
            }
        };
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        analyzer = new MySqlExplainAnalyzer(
                passThrough(jdbcTemplate), new MySqlJsonExplainParser(new ObjectMapper()), "TABULAR");
    }

    // -----------------------------------------------------------------
    // 1. Single table
    // -----------------------------------------------------------------

    @Test
    @DisplayName("single-table query produces one TableAnalysis and fills the flat contract")
    void singleTableQuery() {
        givenExplainRows(row(
                "table", "customers",
                "type", "ref",
                "possible_keys", "idx_city",
                "key", "idx_city",
                "key_len", "203",
                "ref", "const",
                "rows", 2L,
                "Extra", "Using where"));

        QueryAnalysisResult result = analyzer.analyze(
                "SELECT * FROM customers WHERE city = 'mumbai';", "customers");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(result.getTables()).hasSize(1);

        TableAnalysis customers = result.getTables().get(0);
        assertThat(customers.getTableName()).isEqualTo("customers");
        assertThat(customers.getAccessType()).isEqualTo("ref");
        assertThat(customers.getPossibleKeys()).isEqualTo("idx_city");
        assertThat(customers.getKeyUsed()).isEqualTo("idx_city");
        assertThat(customers.getKeyLength()).isEqualTo("203");
        assertThat(customers.getRef()).isEqualTo("const");
        assertThat(customers.getEstimatedRows()).isEqualTo(2L);
        assertThat(customers.isFullTableScan()).isFalse();
        assertThat(customers.isUsesIndex()).isTrue();

        // The flat fields five downstream modules read must still be right.
        assertThat(result.getTableName()).isEqualTo("customers");
        assertThat(result.getAccessType()).isEqualTo("ref");
        assertThat(result.getKeyUsed()).isEqualTo("idx_city");
        assertThat(result.getEstimatedRows()).isEqualTo(2L);
        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.isHasFullTableScan()).isFalse();
    }

    // -----------------------------------------------------------------
    // 2 + 3 + 11. JOINs — the cases the old implementation got wrong
    // -----------------------------------------------------------------

    @Test
    @DisplayName("JOIN over two tables keeps both, and neither overwrites the other")
    void joinKeepsBothTables() {
        givenExplainRows(
                row("table", "c", "type", "ALL", "possible_keys", "PRIMARY", "key", null,
                        "key_len", null, "ref", null, "rows", 303L, "Extra", null),
                row("table", "o", "type", "ref", "possible_keys", "idx_customer_id",
                        "key", "idx_customer_id", "key_len", "5", "ref", "querysense.c.id",
                        "rows", 4L, "Extra", "Using index"));

        QueryAnalysisResult result = analyzer.analyze(
                "SELECT * FROM customers c JOIN orders o ON o.customer_id = c.id;", "customers c");

        assertThat(result.getTables()).hasSize(2);

        TableAnalysis first = result.getTables().get(0);
        TableAnalysis second = result.getTables().get(1);

        // The exact regression: `first` used to end up holding `second`'s values.
        assertThat(first.getTableName()).isEqualTo("c");
        assertThat(first.getAccessType()).isEqualTo("ALL");
        assertThat(first.getKeyUsed()).isNull();
        assertThat(first.isFullTableScan()).isTrue();
        assertThat(first.getEstimatedRows()).isEqualTo(303L);

        assertThat(second.getTableName()).isEqualTo("o");
        assertThat(second.getAccessType()).isEqualTo("ref");
        assertThat(second.getKeyUsed()).isEqualTo("idx_customer_id");
        assertThat(second.getKeyLength()).isEqualTo("5");
        assertThat(second.getRef()).isEqualTo("querysense.c.id");
        assertThat(second.isFullTableScan()).isFalse();
        assertThat(second.getEstimatedRows()).isEqualTo(4L);
    }

    @Test
    @DisplayName("JOIN with one ALL and one ref summarises to the worst access type, not the last row")
    void joinSummarisesToWorstAccessType() {
        givenExplainRows(
                row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                        "key_len", null, "ref", null, "rows", 303L, "Extra", null),
                row("table", "orders", "type", "ref", "possible_keys", "idx_customer_id",
                        "key", "idx_customer_id", "key_len", "5", "ref", "querysense.customers.id",
                        "rows", 4L, "Extra", null));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        // Old behaviour: last row won, so accessType was "ref" and the full
        // scan on customers vanished from the query-level view.
        assertThat(result.getAccessType()).isEqualTo("ALL");
        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isUsesIndex()).isTrue();      // orders did use one
        assertThat(result.getKeyUsed()).isEqualTo("idx_customer_id");
        assertThat(result.getEstimatedRows()).isEqualTo(303L);   // largest, not last
        assertThat(result.getFullScanTables()).containsExactly("customers");

        // The scan warning names the table that was actually scanned...
        assertThat(result.getWarnings())
                .anyMatch(w -> w.contains("Full table scan detected on 'customers'"));
        // ...and no warning claims the query as a whole used no index, which
        // was the old query-wide message and is false here.
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("orders") && w.contains("No index"));
    }

    // -----------------------------------------------------------------
    // 4 + 5. Using temporary / Using filesort
    // -----------------------------------------------------------------

    @Test
    @DisplayName("'Using temporary' is attributed to the table EXPLAIN reported it on")
    void temporaryTableWarningNamesItsTable() {
        givenExplainRows(row("table", "orders", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 90L, "Extra", "Using temporary; Using filesort"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "orders");

        assertThat(result.isUsesTemporaryTable()).isTrue();
        assertThat(result.getTables().get(0).isUsesTemporaryTable()).isTrue();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.startsWith("Temporary table used for 'orders'."));
    }

    @Test
    @DisplayName("'Using filesort' is attributed to its table and does not promise an index will fix it")
    void filesortWarningNamesItsTableAndStaysHonest() {
        givenExplainRows(row("table", "orders", "type", "index", "possible_keys", null,
                "key", "idx_order_date", "key_len", "6", "ref", null, "rows", 90L,
                "Extra", "Using filesort"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "orders");

        assertThat(result.isUsesFilesort()).isTrue();
        String warning = result.getWarnings().stream()
                .filter(w -> w.startsWith("Filesort used for 'orders'."))
                .findFirst()
                .orElseThrow();
        // This project has verified live that an ORDER BY index often does NOT
        // remove a filesort, so the sentence must say "check whether", never
        // "add an index to fix this".
        assertThat(warning).contains("check whether");
        assertThat(warning).doesNotContain("will fix");
    }

    @Test
    @DisplayName("a table mentioned twice in a plan gets each finding only once")
    void warningsAreNotDuplicatedPerTableAndIssue() {
        givenExplainRows(
                row("table", "orders", "type", "ALL", "possible_keys", null, "key", null,
                        "key_len", null, "ref", null, "rows", 90L, "Extra", "Using filesort"),
                row("table", "orders", "type", "ALL", "possible_keys", null, "key", null,
                        "key_len", null, "ref", null, "rows", 90L, "Extra", "Using filesort"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "orders");

        assertThat(result.getWarnings().stream()
                .filter(w -> w.startsWith("Full table scan detected on 'orders'"))
                .count()).isEqualTo(1);
        assertThat(result.getWarnings().stream()
                .filter(w -> w.startsWith("Filesort used for 'orders'"))
                .count()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // 6 + 7. Index used / no index used
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a query using an index produces no 'no index' warning")
    void indexedQueryIsNotWarnedAbout() {
        givenExplainRows(row("table", "customers", "type", "ref", "possible_keys", "idx_city",
                "key", "idx_city", "key_len", "203", "ref", "const", "rows", 2L, "Extra", "Using where"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("No index"));
    }

    @Test
    @DisplayName("full scan warning reports the observation without asserting a missing index")
    void fullScanWarningDoesNotClaimAnIndexIsMissing() {
        givenExplainRows(row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 303L, "Extra", null));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        String warning = result.getWarnings().stream()
                .filter(w -> w.startsWith("Full table scan detected on 'customers'"))
                .findFirst()
                .orElseThrow();
        assertThat(warning).contains("MySQL selected a full scan");
        assertThat(warning).contains("check whether an index");
        // The old wording — "consider adding an index" — asserted the fix.
        assertThat(warning).doesNotContain("consider adding an index");
    }

    @Test
    @DisplayName("a table that used no index and was not scanned is reported with its candidate indexes")
    void unindexedNonScanTableIsReported() {
        givenExplainRows(row("table", "orders", "type", "index_merge", "possible_keys", "idx_status",
                "key", null, "key_len", null, "ref", null, "rows", 50L, "Extra", null));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "orders");

        assertThat(result.isUsesIndex()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.startsWith("No index was used for 'orders'")
                        && w.contains("idx_status"));
    }

    // -----------------------------------------------------------------
    // Column mapping details
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a NULL rows estimate stays null rather than becoming 0")
    void nullRowEstimateIsNotZero() {
        givenExplainRows(row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", null,
                "Extra", "Impossible WHERE noticed after reading const tables"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        assertThat(result.getTables().get(0).getEstimatedRows()).isNull();
        assertThat(result.getEstimatedRows()).isNull();
    }

    @Test
    @DisplayName("the query-level table name comes from EXPLAIN, not from the parser's aliased FROM item")
    void primaryTableNameComesFromExplain() {
        givenExplainRows(row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 303L, "Extra", null));

        // SqlStructureParser hands over the raw FROM item, alias included.
        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers c");

        assertThat(result.getTableName()).isEqualTo("customers");
    }

    @Test
    @DisplayName("a derived-table placeholder does not become the query-level table name")
    void derivedPlaceholderFallsBackToTheCallerName() {
        givenExplainRows(row("table", "<derived2>", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 10L, "Extra", null));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        assertThat(result.getTableName()).isEqualTo("customers");
    }

    // -----------------------------------------------------------------
    // 8 + 9 + 10. Failure modes
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an unknown column (MySQL 1054) is reported as a column problem")
    void unknownColumnGetsItsOwnMessage() {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenThrow(badGrammar(1054));

        QueryAnalysisResult result = analyzer.analyze("SELECT age FROM customers;", "customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.contains("references a column that doesn't exist"));
    }

    @Test
    @DisplayName("an ONLY_FULL_GROUP_BY violation (MySQL 1055) names the GROUP BY rule")
    void onlyFullGroupByGetsItsOwnMessage() {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenThrow(badGrammar(1055));

        QueryAnalysisResult result = analyzer.analyze("SELECT name, COUNT(*) FROM customers;", "customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("ONLY_FULL_GROUP_BY"));
    }

    @Test
    @DisplayName("an unreachable database is reported as infrastructure, not as bad SQL")
    void unreachableDatabaseIsNotBlamedOnTheQuery() {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("database could not be reached"));
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("check that it references valid"));
    }

    @Test
    @DisplayName("unusable EXPLAIN output (the h2 profile) keeps the message it has always produced")
    void unusableExplainOutputKeepsTheLegacyMessage() {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenThrow(new InvalidDataAccessResourceUsageException("column 'type' not found"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.startsWith("This query could not be analyzed by MySQL"));
    }

    @Test
    @DisplayName("an analyzer bug is reported as an internal error, not disguised as bad SQL")
    void analyzerBugIsNotDisguisedAsASqlProblem() {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenThrow(new IllegalStateException("boom"));

        QueryAnalysisResult result = analyzer.analyze("SELECT ...", "customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("problem in QuerySense"));
        // The whole point: this must NOT read like the user's SQL is at fault.
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("check that it references valid"));
    }

    // -----------------------------------------------------------------
    // Safety
    // -----------------------------------------------------------------

    @Test
    @DisplayName("only an EXPLAIN is ever sent — the query itself is never executed")
    void onlyExplainIsExecuted() {
        givenExplainRows(row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 3L, "Extra", null));

        analyzer.analyze("SELECT * FROM customers WHERE city = 'mumbai';", "customers");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), ArgumentMatchers.<RowMapper<TableAnalysis>>any());
        assertThat(sql.getValue()).startsWith("EXPLAIN ");
        assertThat(sql.getValue()).doesNotContain("EXPLAIN ANALYZE");
        assertThat(sql.getValue()).doesNotEndWith(";");
    }

    @Test
    @DisplayName("tabular mode names TRADITIONAL explicitly — a bare EXPLAIN is tree-shaped on MySQL 9")
    void tabularModeRequestsTraditionalFormat() {
        givenExplainRows(row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 303L, "Extra", null));

        analyzer.analyze("SELECT * FROM customers WHERE city = 'mumbai';", "customers");

        // WHY THIS IS ASSERTED ON THE SQL STRING RATHER THAN ON A RESULT:
        // MySQL 9.0 changed the default value of the explain_format system
        // variable from TRADITIONAL to TREE. A bare "EXPLAIN <query>" therefore
        // returns one column named `EXPLAIN` holding a tree diagram on a 9.x
        // server, and the row mapper — which needs `table`, `type`, `key`,
        // `rows` and `Extra` — cannot read it. The failure surfaces as
        // explainFailed=true carrying a warning that blames the user's SQL, so
        // no assertion about the returned object can distinguish it from an
        // actually-invalid query. The request itself is the only place the
        // regression is visible, which is why the format is pinned here.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), ArgumentMatchers.<RowMapper<TableAnalysis>>any());
        assertThat(sql.getValue()).startsWith("EXPLAIN FORMAT=TRADITIONAL ");
    }

    // -----------------------------------------------------------------
    // EXPLAIN FORMAT=JSON (opt-in)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("JSON mode reads the plan from EXPLAIN FORMAT=JSON and skips the tabular call")
    void jsonModeUsesJsonExplain() {
        when(jdbcTemplate.queryForObject(anyString(), ArgumentMatchers.eq(String.class)))
                .thenReturn("""
                        {"query_block": {"table": {
                            "table_name": "customers", "access_type": "ALL",
                            "rows_examined_per_scan": 303 }}}
                        """);

        QueryAnalysisResult result = jsonModeAnalyzer().analyze("SELECT ...", "customers");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(result.getTables()).hasSize(1);
        assertThat(result.getTables().get(0).getTableName()).isEqualTo("customers");
        assertThat(result.isHasFullTableScan()).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), ArgumentMatchers.eq(String.class));
        assertThat(sql.getValue()).startsWith("EXPLAIN FORMAT=JSON ");
    }

    @Test
    @DisplayName("a broken JSON plan falls back to tabular rather than failing the analysis")
    void jsonModeFallsBackToTabular() {
        when(jdbcTemplate.queryForObject(anyString(), ArgumentMatchers.eq(String.class)))
                .thenReturn("{not json");
        givenExplainRows(row("table", "customers", "type", "ref", "possible_keys", "idx_city",
                "key", "idx_city", "key_len", "203", "ref", "const", "rows", 2L, "Extra", null));

        QueryAnalysisResult result = jsonModeAnalyzer().analyze("SELECT ...", "customers");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(result.getAccessType()).isEqualTo("ref");
        assertThat(result.getKeyUsed()).isEqualTo("idx_city");
    }

    @Test
    @DisplayName("an unrecognised format setting degrades to TABULAR instead of throwing at startup")
    void unknownFormatSettingDegradesToTabular() {
        givenExplainRows(row("table", "customers", "type", "ALL", "possible_keys", null, "key", null,
                "key_len", null, "ref", null, "rows", 3L, "Extra", null));

        QueryAnalysisResult result = new MySqlExplainAnalyzer(
                passThrough(jdbcTemplate), new MySqlJsonExplainParser(new ObjectMapper()), "nonsense")
                .analyze("SELECT ...", "customers");

        assertThat(result.getTables()).hasSize(1);
    }

    private MySqlExplainAnalyzer jsonModeAnalyzer() {
        return new MySqlExplainAnalyzer(
                passThrough(jdbcTemplate), new MySqlJsonExplainParser(new ObjectMapper()), "JSON");
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Stubs JdbcTemplate to hand the analyzer's OWN RowMapper a fake
     * ResultSet per row, so the column reads under test are the real ones.
     */
    @SafeVarargs
    private void givenExplainRows(Map<String, Object>... rows) {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<TableAnalysis> mapper = invocation.getArgument(1);
                    List<TableAnalysis> mapped = new ArrayList<>();
                    int rowNum = 0;
                    for (Map<String, Object> row : rows) {
                        mapped.add(mapper.mapRow(resultSetOf(row), rowNum++));
                    }
                    return mapped;
                });
    }

    /** LinkedHashMap rather than Map.of, because EXPLAIN columns are genuinely null sometimes. */
    private static Map<String, Object> row(Object... keyValuePairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            row.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return row;
    }

    /** A ResultSet over one row, answering by column label the way JDBC does. */
    private static ResultSet resultSetOf(Map<String, Object> values) throws SQLException {
        List<String> labels = new ArrayList<>(values.keySet());

        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(labels.size());
        when(metaData.getColumnLabel(anyInt()))
                .thenAnswer(invocation -> labels.get((int) invocation.getArgument(0) - 1));

        boolean[] lastReadWasNull = {false};
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(resultSet.getString(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.<String>getArgument(0));
            lastReadWasNull[0] = value == null;
            return value == null ? null : String.valueOf(value);
        });
        when(resultSet.getLong(anyString())).thenAnswer(invocation -> {
            Object value = values.get(invocation.<String>getArgument(0));
            lastReadWasNull[0] = value == null;
            return value == null ? 0L : ((Number) value).longValue();
        });
        when(resultSet.wasNull()).thenAnswer(invocation -> lastReadWasNull[0]);
        return resultSet;
    }

    private static BadSqlGrammarException badGrammar(int mysqlErrorCode) {
        return new BadSqlGrammarException("StatementCallback", "EXPLAIN ...",
                new SQLException("mysql error", "42000", mysqlErrorCode));
    }
}
