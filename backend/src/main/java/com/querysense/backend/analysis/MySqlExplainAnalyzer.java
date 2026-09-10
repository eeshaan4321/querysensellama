package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySqlExplainAnalyzer
 *
 * WHY THIS CLASS EXISTS:
 * This is the core of the Query Analysis Engine's MySQL half. It runs
 * EXPLAIN on a SELECT query and translates MySQL's raw execution-plan
 * output into the shared QueryAnalysisResult contract that every other
 * module (Domain Optimization, Schema Advisor, Comparison Engine) reads.
 *
 * WHY JdbcTemplate over raw JDBC?
 * Spring's JdbcTemplate handles connection acquisition/release and
 * exception translation for us. We already use JPA/Hibernate for the
 * History module, but EXPLAIN's result set doesn't map to any @Entity —
 * it's a one-off structural query, so plain JdbcTemplate is the right
 * tool here rather than forcing it through the JPA layer.
 *
 * WHAT CHANGED, AND WHY:
 * EXPLAIN emits one row per table. The first version of this class mapped
 * every row onto the same flat result, so on a JOIN the second row silently
 * overwrote the first row's accessType and keyUsed — the plan
 *
 *     customers -> ALL -> no index
 *     orders    -> ref -> idx_customer_id
 *
 * was reported as a single table with whichever row MySQL happened to emit
 * last, and the warning could not name the table that was actually scanned.
 * Every row now produces its own TableAnalysis (see that class), and the
 * flat fields on QueryAnalysisResult are computed as an explicit summary
 * over those rows rather than as a side effect of iteration order. The flat
 * fields are unchanged in name, type and meaning, because five downstream
 * modules and the frontend already read them.
 *
 * SAFETY:
 * This class ONLY ever prefixes the given query with "EXPLAIN " (or
 * "EXPLAIN FORMAT=JSON " in the opt-in JSON mode). It never executes the
 * query itself, and it deliberately does not offer EXPLAIN ANALYZE, which
 * would. Callers (the orchestrating QueryAnalysisService) are responsible
 * for making sure only SELECT-type queries reach this class — EXPLAIN on a
 * DELETE/UPDATE analyzes the plan without running it, but we still don't
 * want non-SELECT queries here since MySQL's EXPLAIN output columns behave
 * differently for them and it's outside this module's scope.
 */
@Component
public class MySqlExplainAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MySqlExplainAnalyzer.class);

    /**
     * MySQL's join types, best (most selective) first. Used to pick the
     * query-level accessType: we report the WORST one in the plan, so a
     * full scan on the driving table is never masked by a cheap indexed
     * lookup on a table joined after it. Anything MySQL reports that isn't
     * in this list ranks below everything here, i.e. it never wins.
     */
    private static final List<String> ACCESS_TYPES_BEST_TO_WORST = List.of(
            "system", "const", "eq_ref", "ref", "fulltext", "ref_or_null",
            "index_merge", "unique_subquery", "index_subquery", "range", "index", "all");

    /** Dedupe keys for warnings, so one table never gets the same finding twice. */
    private static final String ISSUE_FULL_SCAN = "full-scan";
    private static final String ISSUE_NO_INDEX = "no-index";
    private static final String ISSUE_FILESORT = "filesort";
    private static final String ISSUE_TEMP_TABLE = "temp-table";

    /**
     * Maps one row of MySQL's tabular EXPLAIN output.
     *
     * WHY it checks the columns first: under the `h2` profile (and anywhere
     * else that isn't MySQL) EXPLAIN succeeds but returns a completely
     * different result set. Failing here with a clear SQLException routes
     * that into the "could not be analyzed" path below, which is what the
     * H2 profile has always reported, instead of a confusing NPE deeper in.
     * Optional columns are read only when present so a narrower EXPLAIN
     * dialect still yields the fields it does have.
     */
    private static final RowMapper<TableAnalysis> EXPLAIN_ROW_MAPPER = (rs, rowNum) -> {
        Set<String> columns = columnLabels(rs);
        if (!columns.contains("table") || !columns.contains("type")) {
            throw new SQLException("EXPLAIN did not return MySQL's execution-plan columns "
                    + "(expected at least 'table' and 'type'; got " + columns + ")");
        }

        TableAnalysis analysis = new TableAnalysis();
        analysis.setTableName(rs.getString("table"));
        analysis.setAccessType(rs.getString("type"));
        analysis.setPossibleKeys(optionalString(rs, columns, "possible_keys"));
        analysis.setKeyUsed(optionalString(rs, columns, "key"));
        analysis.setKeyLength(optionalString(rs, columns, "key_len"));
        analysis.setRef(optionalString(rs, columns, "ref"));

        if (columns.contains("rows")) {
            long rows = rs.getLong("rows");
            // EXPLAIN reports NULL rows for plans like "Impossible WHERE";
            // 0 rows and "not reported" are different claims.
            analysis.setEstimatedRows(rs.wasNull() ? null : rows);
        }

        String extra = optionalString(rs, columns, "Extra");
        analysis.setExtra(extra);
        analysis.setFullTableScan("ALL".equalsIgnoreCase(analysis.getAccessType()));
        analysis.setUsesIndex(analysis.getKeyUsed() != null);
        analysis.setUsesFilesort(extra != null && extra.contains("Using filesort"));
        analysis.setUsesTemporaryTable(extra != null && extra.contains("Using temporary"));

        return analysis;
    };

    /** Which EXPLAIN dialect to ask MySQL for. See MySqlJsonExplainParser for why JSON is opt-in. */
    public enum ExplainFormat { TABULAR, JSON }

    /**
     * WHY AN ExplainExecutor RATHER THAN A JdbcTemplate DIRECTLY:
     * EXPLAIN must run against whichever database the ACTIVE schema
     * describes, and once the user uploads a schema that is a different
     * catalog from the one spring.datasource.url points at. The executor
     * switches and restores the catalog on a single pooled connection.
     *
     * On the built-in schema the active catalog is null and the executor
     * is a no-op, so this class issues exactly the statements it always
     * did — see ExplainExecutorTest, which asserts that rather than
     * trusting it.
     */
    private final ExplainExecutor explainExecutor;
    private final MySqlJsonExplainParser jsonExplainParser;
    private final ExplainFormat explainFormat;

    public MySqlExplainAnalyzer(ExplainExecutor explainExecutor,
                                MySqlJsonExplainParser jsonExplainParser,
                                @Value("${querysense.explain.format:TABULAR}") String explainFormat) {
        this.explainExecutor = explainExecutor;
        this.jsonExplainParser = jsonExplainParser;
        this.explainFormat = parseFormat(explainFormat);
    }

    private static ExplainFormat parseFormat(String configured) {
        try {
            return ExplainFormat.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Unrecognised querysense.explain.format '{}' — using TABULAR.", configured);
            return ExplainFormat.TABULAR;
        }
    }

    /**
     * Runs EXPLAIN on the given SELECT query and populates a QueryAnalysisResult
     * with the execution-plan signals other modules depend on.
     *
     * @param sqlQuery   a SELECT statement (caller must guarantee this — see class doc)
     * @param tableName  the primary table this query targets, as SqlStructureParser
     *                   read it from the SQL text. Used as the fallback name when
     *                   EXPLAIN itself can't supply one (and when EXPLAIN fails
     *                   outright); the per-table names always come from EXPLAIN.
     * @return           a populated QueryAnalysisResult, or one with a warning if EXPLAIN failed
     */
    public QueryAnalysisResult analyze(String sqlQuery, String tableName) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setTableName(tableName);
        result.setDatabaseType("MySQL");
        result.setQueryType("SELECT");

        String query = stripTrailingSemicolon(sqlQuery);

        try {
            List<TableAnalysis> tables = readPlan(query);
            result.setTables(tables);
            summarise(result, tables, tableName);
        } catch (BadSqlGrammarException e) {
            // The user's SQL is wrong. WHY a friendly message instead of
            // e.getMessage(): BadSqlGrammarException.toString() is a raw
            // JDBC/driver string ("StatementCallback; bad SQL grammar [...]") —
            // exactly the kind of thing warnings must NOT be (they're complete,
            // human-readable sentences shown directly in the UI). The real
            // exception is still logged server-side; it never reaches the body.
            log.warn("EXPLAIN rejected the query on table '{}': {}", tableName, e.getMessage(), e);
            result.addWarning(friendlyMessageFor(e));
            result.setExplainFailed(true);
        } catch (DataAccessResourceFailureException e) {
            // The database is unreachable. Nothing is wrong with the SQL, so
            // saying "check your columns" would send the reader down the wrong
            // path — this is the one failure that is squarely infrastructure.
            log.warn("EXPLAIN could not reach the database for table '{}': {}", tableName, e.getMessage(), e);
            result.addWarning("The database could not be reached, so this query's execution plan "
                    + "could not be analyzed. The query itself was not run.");
            result.setExplainFailed(true);
        } catch (DataAccessException e) {
            // MySQL (or whatever engine is configured) ran EXPLAIN but the
            // result was not something we can read — the usual cause is the
            // h2 profile, where EXPLAIN returns a different result set
            // entirely. Same sentence this has always produced.
            log.warn("EXPLAIN returned unusable output for table '{}': {}", tableName, e.getMessage(), e);
            result.addWarning("This query could not be analyzed by MySQL — check that it references valid "
                    + "columns and is syntactically correct under strict SQL mode.");
            result.setExplainFailed(true);
        } catch (RuntimeException e) {
            // A bug in this analyzer, not in the user's SQL. Previously this
            // was swallowed by the same catch as a bad column name and
            // reported as if the query were at fault, which hid real defects.
            // Logged at ERROR so it shows up as what it is.
            log.error("Unexpected failure while analyzing the execution plan for table '{}'. "
                    + "This is a QuerySense defect, not a problem with the query.", tableName, e);
            result.addWarning("An internal error prevented the execution-plan analysis from completing. "
                    + "The query itself was not run, and this is a problem in QuerySense rather than "
                    + "in your SQL.");
            result.setExplainFailed(true);
        }

        return result;
    }

    // -----------------------------------------------------------------
    // Reading the plan
    // -----------------------------------------------------------------

    /**
     * Asks MySQL for the plan in the configured format. JSON is attempted
     * first only when explicitly enabled, and any JSON-side problem falls
     * back to the tabular reader rather than failing the analysis — a bad
     * strategy for a nicer format shouldn't cost the caller its plan. A
     * BadSqlGrammarException is rethrown instead, because that means the
     * query is wrong and the tabular attempt would fail identically.
     */
    private List<TableAnalysis> readPlan(String query) {
        return explainExecutor.withActiveCatalog(jdbc -> readPlanWith(jdbc, query));
    }

    /**
     * @param jdbc a JdbcTemplate bound to one connection already pointing at
     *             the active schema's catalog. Valid only for this call.
     */
    private List<TableAnalysis> readPlanWith(JdbcTemplate jdbc, String query) {
        if (explainFormat == ExplainFormat.JSON) {
            try {
                String json = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + query, String.class);
                List<TableAnalysis> tables = jsonExplainParser.parse(json);
                if (!tables.isEmpty()) {
                    return tables;
                }
                log.warn("EXPLAIN FORMAT=JSON returned no table nodes — falling back to tabular EXPLAIN.");
            } catch (BadSqlGrammarException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("EXPLAIN FORMAT=JSON failed ({}) — falling back to tabular EXPLAIN.", e.toString());
            }
        }
        // FORMAT=TRADITIONAL is stated rather than left to the server default.
        // MySQL 9.0 changed the default explain_format from TRADITIONAL to
        // TREE, so a bare "EXPLAIN <query>" returns a single column named
        // EXPLAIN holding a tree diagram on any 9.x server — none of the
        // columns EXPLAIN_ROW_MAPPER reads (`table`, `type`, `key`, `rows`,
        // `Extra`) are present, and the analysis fails with a warning that
        // blames the user's SQL. Naming the format makes this independent of
        // both the server version and any session-level explain_format a DBA
        // has set. The clause is accepted by 8.0 and 9.x alike.
        return jdbc.query("EXPLAIN FORMAT=TRADITIONAL " + query, EXPLAIN_ROW_MAPPER);
    }

    // -----------------------------------------------------------------
    // Summarising the plan onto the flat contract
    // -----------------------------------------------------------------

    /**
     * Folds the per-table rows into the query-level fields every existing
     * consumer reads, and emits one set of warnings per table.
     *
     * The reductions are stated on QueryAnalysisResult: worst access type,
     * largest row estimate, first index used, and "any table" for each of
     * the four booleans. "Any table" is exactly what the previous version
     * produced for the booleans, so nothing downstream changes behaviour;
     * accessType and keyUsed are the two that were previously last-row-wins
     * and now have a defined meaning.
     */
    private void summarise(QueryAnalysisResult result, List<TableAnalysis> tables, String fallbackTableName) {
        if (tables.isEmpty()) {
            return;
        }

        result.setTableName(primaryTableName(tables, fallbackTableName));

        Set<String> emittedWarnings = new LinkedHashSet<>();
        TableAnalysis worstTable = null;
        Long largestRowEstimate = null;
        String firstKeyUsed = null;

        for (TableAnalysis table : tables) {
            if (table.isFullTableScan()) {
                result.setHasFullTableScan(true);
            }
            if (table.isUsesIndex()) {
                result.setUsesIndex(true);
            }
            if (table.isUsesFilesort()) {
                result.setUsesFilesort(true);
            }
            if (table.isUsesTemporaryTable()) {
                result.setUsesTemporaryTable(true);
            }

            Long rows = table.getEstimatedRows();
            if (rows != null && (largestRowEstimate == null || rows > largestRowEstimate)) {
                largestRowEstimate = rows;
            }
            if (firstKeyUsed == null && table.getKeyUsed() != null) {
                firstKeyUsed = table.getKeyUsed();
            }
            if (worstTable == null || rankOf(table.getAccessType()) > rankOf(worstTable.getAccessType())) {
                worstTable = table;
            }

            addWarningsFor(result, table, emittedWarnings);
        }

        result.setEstimatedRows(largestRowEstimate);
        result.setKeyUsed(firstKeyUsed);
        result.setAccessType(worstTable.getAccessType());
    }

    /**
     * The table the query-level fields are attributed to: the first real
     * table in plan order, which is MySQL's driving table.
     *
     * WHY not just keep the caller's tableName: SqlStructureParser hands us
     * the raw FROM item, so "FROM customers" arrives fine but "FROM orders
     * o" arrives as "orders o" — and the Schema Advisor pastes that straight
     * into a CREATE INDEX statement. EXPLAIN's own name is clean for the
     * unaliased case, which is what this project's rule engine generates.
     *
     * LIMIT, verified live: when the query DOES alias a table, EXPLAIN's
     * `table` column reports the ALIAS ("c"), not "customers" — so an
     * aliased query still yields a name that can't be indexed directly.
     * That is no worse than the "customers c" it produced before, but it is
     * not fixed either; resolving aliases needs the FROM/JOIN alias map from
     * SqlStructureParser's AST, which is a separate change.
     *
     * Derived and UNION placeholders ("&lt;derived2&gt;") are skipped because
     * they name no table anyone can index; if the plan is only placeholders,
     * the caller's value stands.
     */
    private String primaryTableName(List<TableAnalysis> tables, String fallbackTableName) {
        for (TableAnalysis table : tables) {
            String name = table.getTableName();
            if (name != null && !name.isBlank() && !name.startsWith("<")) {
                return name;
            }
        }
        return fallbackTableName;
    }

    /**
     * Rank within ACCESS_TYPES_BEST_TO_WORST; unknown or absent types rank
     * -1 so they never win the "worst" comparison against a type we do know.
     */
    private int rankOf(String accessType) {
        if (accessType == null) {
            return -1;
        }
        return ACCESS_TYPES_BEST_TO_WORST.indexOf(accessType.toLowerCase(Locale.ROOT));
    }

    // -----------------------------------------------------------------
    // Warnings
    // -----------------------------------------------------------------

    /**
     * Emits the findings for one table, each naming the table it belongs to.
     *
     * WORDING RULE: EXPLAIN tells us what MySQL chose, not why it was wrong.
     * "ALL" is not proof that an index is missing — it is often the correct
     * plan for a small table, and MySQL will choose it over an index that
     * exists. So these sentences report the observation and suggest what to
     * check, and never assert that adding an index will fix anything.
     *
     * `emitted` holds "table|issue" keys so a plan that mentions the same
     * table more than once cannot produce the same sentence twice.
     */
    private void addWarningsFor(QueryAnalysisResult result, TableAnalysis table, Set<String> emitted) {
        String name = displayNameOf(table);

        if (table.isFullTableScan() && emitted.add(name + "|" + ISSUE_FULL_SCAN)) {
            result.addWarning("Full table scan detected on '" + name + "'. MySQL selected a full scan "
                    + "for this table. If the query is slow, check whether an index on the filtered or "
                    + "joined columns could reduce the rows examined.");
        }

        // Only for a table that used no index AND wasn't already reported as a
        // full scan — otherwise the same table gets two sentences about the
        // same fact. Note this is per table: a JOIN where one side is indexed
        // no longer produces the old query-wide "no index is being used"
        // claim, which was wrong whenever any other table used one.
        if (!table.isUsesIndex() && !table.isFullTableScan() && table.getAccessType() != null
                && emitted.add(name + "|" + ISSUE_NO_INDEX)) {
            result.addWarning("No index was used for '" + name + "' (access type '"
                    + table.getAccessType() + "'). Indexes considered: "
                    + (table.getPossibleKeys() != null ? table.getPossibleKeys() : "none") + ".");
        }

        if (table.isUsesTemporaryTable() && emitted.add(name + "|" + ISSUE_TEMP_TABLE)) {
            result.addWarning("Temporary table used for '" + name + "'. This may increase work for "
                    + "GROUP BY or DISTINCT operations.");
        }

        if (table.isUsesFilesort() && emitted.add(name + "|" + ISSUE_FILESORT)) {
            result.addWarning("Filesort used for '" + name + "'. If the query is slow, check whether an "
                    + "index matching the ORDER BY columns could reduce sorting work.");
        }
    }

    private String displayNameOf(TableAnalysis table) {
        String name = table.getTableName();
        return name != null && !name.isBlank() ? name : "this query";
    }

    /**
     * Translates the underlying MySQL error code into a specific,
     * actionable sentence where we can (1054 = unknown column, 1055 = an
     * ONLY_FULL_GROUP_BY violation — the two failure modes this project has
     * actually hit), falling back to a generic-but-still-friendly message
     * for anything else, per the cross-cutting note in this project's bug
     * tracker: a strict-mode violation, a hallucinated column, and a plain
     * syntax typo shouldn't all look identical to the user.
     */
    private String friendlyMessageFor(BadSqlGrammarException e) {
        SQLException sqlException = e.getSQLException();
        int errorCode = sqlException != null ? sqlException.getErrorCode() : -1;

        return switch (errorCode) {
            case 1054 -> "This query references a column that doesn't exist on this table — "
                    + "check the column names and try again.";
            case 1055 -> "This query's GROUP BY clause doesn't include every selected column, which MySQL's "
                    + "strict mode (ONLY_FULL_GROUP_BY) requires — add the missing column to GROUP BY or wrap "
                    + "it in an aggregate function.";
            default -> "This query could not be analyzed by MySQL — check that it references valid columns "
                    + "and is syntactically correct under strict SQL mode.";
        };
    }

    // -----------------------------------------------------------------
    // ResultSet helpers
    // -----------------------------------------------------------------

    /** Lower-cased column labels of the current result set, for presence checks. */
    private static Set<String> columnLabels(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            if (label != null) {
                labels.add(label.toLowerCase(Locale.ROOT));
            }
        }
        return labels;
    }

    /** Reads a column only if the result set actually has it; blank becomes null. */
    private static String optionalString(ResultSet rs, Set<String> columns, String label)
            throws SQLException {
        if (!columns.contains(label.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String value = rs.getString(label);
        return value != null && !value.isBlank() ? value : null;
    }

    private String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
