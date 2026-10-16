package com.querysense.backend.analysis;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.PlanNode;
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

/** Runs MySQL EXPLAIN on a query and maps the plan into a QueryAnalysisResult. */
@Component
public class MySqlExplainAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MySqlExplainAnalyzer.class);

    private static final List<String> ACCESS_TYPES_BEST_TO_WORST = List.of(
            "system", "const", "eq_ref", "ref", "fulltext", "ref_or_null",
            "index_merge", "unique_subquery", "index_subquery", "range", "index", "all");

    private static final String ISSUE_FULL_SCAN = "full-scan";
    private static final String ISSUE_NO_INDEX = "no-index";
    private static final String ISSUE_FILESORT = "filesort";
    private static final String ISSUE_TEMP_TABLE = "temp-table";

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
            analysis.setEstimatedRows(rs.wasNull() ? null : rows);
        }

        if (columns.contains("filtered")) {
            double filtered = rs.getDouble("filtered");
            analysis.setFiltered(rs.wasNull() ? null : filtered);
        }

        String extra = optionalString(rs, columns, "Extra");
        analysis.setExtra(extra);
        analysis.setFullTableScan("ALL".equalsIgnoreCase(analysis.getAccessType()));
        analysis.setUsesIndex(analysis.getKeyUsed() != null);
        analysis.setUsesFilesort(extra != null && extra.contains("Using filesort"));
        analysis.setUsesTemporaryTable(extra != null && extra.contains("Using temporary"));

        return analysis;
    };

    public enum ExplainFormat { TABULAR, JSON }

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

    public QueryAnalysisResult analyze(String sqlQuery, String tableName) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setTableName(tableName);
        result.setDatabaseType("MySQL");
        result.setQueryType("SELECT");

        String query = stripTrailingSemicolon(sqlQuery);

        try {
            PlanRead plan = readPlan(query);
            result.setTables(plan.tables());
            result.setQueryCost(plan.queryCost());
            result.setPlanTree(plan.planTree());
            summarise(result, plan.tables(), tableName);
        } catch (BadSqlGrammarException e) {
            log.warn("EXPLAIN rejected the query on table '{}': {}", tableName, e.getMessage(), e);
            result.addWarning(friendlyMessageFor(e));
            result.setExplainFailed(true);
        } catch (DataAccessResourceFailureException e) {
            log.warn("EXPLAIN could not reach the database for table '{}': {}", tableName, e.getMessage(), e);
            result.addWarning("The database could not be reached, so this query's execution plan "
                    + "could not be analyzed. The query itself was not run.");
            result.setExplainFailed(true);
        } catch (DataAccessException e) {
            log.warn("EXPLAIN returned unusable output for table '{}': {}", tableName, e.getMessage(), e);
            result.addWarning("This query could not be analyzed by MySQL — check that it references valid "
                    + "columns and is syntactically correct under strict SQL mode.");
            result.setExplainFailed(true);
        } catch (RuntimeException e) {
            log.error("Unexpected failure while analyzing the execution plan for table '{}'. "
                    + "This is a QuerySense defect, not a problem with the query.", tableName, e);
            result.addWarning("An internal error prevented the execution-plan analysis from completing. "
                    + "The query itself was not run, and this is a problem in QuerySense rather than "
                    + "in your SQL.");
            result.setExplainFailed(true);
        }

        return result;
    }

    private PlanRead readPlan(String query) {
        return explainExecutor.withActiveCatalog(jdbc -> readPlanWith(jdbc, query));
    }

    private record PlanRead(List<TableAnalysis> tables, Double queryCost, PlanNode planTree) {}

    private PlanRead readPlanWith(JdbcTemplate jdbc, String query) {
        if (explainFormat == ExplainFormat.JSON) {
            try {
                pinJsonFormatVersion(jdbc);
                String json = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + query, String.class);
                List<TableAnalysis> tables = jsonExplainParser.parse(json);
                if (!tables.isEmpty()) {
                    return new PlanRead(tables, jsonExplainParser.parseQueryCost(json),
                            jsonExplainParser.parsePlanTree(json));
                }
                log.warn("EXPLAIN FORMAT=JSON returned no table nodes — falling back to tabular EXPLAIN.");
            } catch (BadSqlGrammarException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("EXPLAIN FORMAT=JSON failed ({}) — falling back to tabular EXPLAIN.", e.toString());
            }
        }
        List<TableAnalysis> tables = jdbc.query("EXPLAIN FORMAT=TRADITIONAL " + query, EXPLAIN_ROW_MAPPER);
        JsonProbe probe = readJsonProbe(jdbc, query);
        return new PlanRead(tables, probe.queryCost(), probe.planTree());
    }

    private record JsonProbe(Double queryCost, PlanNode planTree) {}

    /**
     * One side probe serves both the cost figure and the plan tree; the tabular read above
     * stays the authority on the plan itself.
     */
    private JsonProbe readJsonProbe(JdbcTemplate jdbc, String query) {
        try {
            pinJsonFormatVersion(jdbc);
            String json = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + query, String.class);
            return new JsonProbe(jsonExplainParser.parseQueryCost(json),
                    jsonExplainParser.parsePlanTree(json));
        } catch (RuntimeException e) {
            log.debug("JSON probe returned nothing usable ({}) — omitting the cost and the plan tree.",
                    e.toString());
            return new JsonProbe(null, null);
        }
    }

    private static void pinJsonFormatVersion(JdbcTemplate jdbc) {
        try {
            jdbc.execute("SET SESSION explain_json_format_version = 1");
        } catch (RuntimeException e) {
            log.debug("explain_json_format_version is not settable here ({}); the server's JSON is version 1.",
                    e.toString());
        }
    }

    private void summarise(QueryAnalysisResult result, List<TableAnalysis> tables, String fallbackTableName) {
        if (tables.isEmpty()) {
            return;
        }

        result.setTableName(primaryTableName(tables, fallbackTableName));

        Set<String> emittedWarnings = new LinkedHashSet<>();
        TableAnalysis worstTable = null;
        TableAnalysis heaviestTable = null;
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
                heaviestTable = table;
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

        if (heaviestTable != null) {
            result.setFiltered(heaviestTable.getFiltered());
            result.setEstimatedRowsReturned(heaviestTable.getEstimatedRowsReturned());
        }
    }

    private String primaryTableName(List<TableAnalysis> tables, String fallbackTableName) {
        for (TableAnalysis table : tables) {
            String name = table.getTableName();
            if (name != null && !name.isBlank() && !name.startsWith("<")) {
                return name;
            }
        }
        return fallbackTableName;
    }

    private int rankOf(String accessType) {
        if (accessType == null) {
            return -1;
        }
        return ACCESS_TYPES_BEST_TO_WORST.indexOf(accessType.toLowerCase(Locale.ROOT));
    }

    private void addWarningsFor(QueryAnalysisResult result, TableAnalysis table, Set<String> emitted) {
        String name = displayNameOf(table);

        if (table.isFullTableScan() && emitted.add(name + "|" + ISSUE_FULL_SCAN)) {
            result.addWarning("Full table scan detected on '" + name + "'. MySQL selected a full scan "
                    + "for this table. If the query is slow, check whether an index on the filtered or "
                    + "joined columns could reduce the rows examined.");
        }

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
