package com.querysense.backend.analysis;

import com.querysense.backend.schema.ActiveSchemaProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SchemaMetadataService
 *
 * WHY THIS CLASS EXISTS:
 * Table/column STRUCTURE (which tables exist, which columns they have) is
 * owned centrally by the ACTIVE schema, read through
 * {@link com.querysense.backend.schema.ActiveSchemaProvider} — either the
 * built-in KnownSchema or one the user uploaded. Previously this class and
 * RuleBasedQueryBuilder each hardcoded their own incomplete copy. This
 * class's remaining job is the thing a declared schema genuinely can't
 * express: live facts from the actual running database.
 *
 * - {@link #getColumnNames(String)} is a live safety net for
 *   SqlPreflightValidator — it catches schema DRIFT (a column added/removed
 *   after the schema was last declared) that a declared list can't see, by
 *   double-checking a generated query against what MySQL actually has.
 * - {@link #getDistinctValues(String, String)} looks up real DATA values
 *   (e.g. the actual city names in `customers.city`) — RuleBasedQueryBuilder
 *   uses this to detect when a real, known value is mentioned in a request
 *   but its clause matcher failed to capture it, so it can bail to the LLM fallback
 *   instead of silently dropping the filter (Bugs 8/10).
 *
 * Both read from information_schema / the table itself rather than a
 * hardcoded list, and both are cached since this data is practically static
 * within a running session.
 */
@Component
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ActiveSchemaProvider activeSchema;
    private final Map<String, List<String>> columnCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();

    public SchemaMetadataService(JdbcTemplate jdbcTemplate, ActiveSchemaProvider activeSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeSchema = activeSchema;
    }

    /**
     * WHY THE CACHES MUST BE FLUSHED WHEN THE SCHEMA CHANGES:
     * Both caches are keyed by bare table name and never evict - fine while
     * the schema was static and compiled in. Once it can be replaced at
     * runtime, a stale entry means this service keeps answering with the
     * PREVIOUS schema's columns and values, and every caller downstream
     * (SqlPreflightValidator, the rule engine's known-city safety net)
     * looks correct while being wrong.
     *
     * Registered as a listener rather than called from ActiveSchemaProvider
     * because this class already depends on that one; the reverse edge
     * would be a cycle.
     */
    
    void registerSchemaChangeListener() {
        activeSchema.onSchemaChanged(this::clearCaches);
    }

    public void clearCaches() {
        columnCache.clear();
        distinctValueCache.clear();
    }

    /**
     * Returns the real column names for a table, or an empty list if the
     * table doesn't exist or the lookup fails. Callers must treat an empty
     * list as "unknown" rather than "this table has no columns" and degrade
     * gracefully instead of blocking on it — same pattern as every other
     * analyzer in this package.
     */
    public List<String> getColumnNames(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        return columnCache.computeIfAbsent(tableName.toLowerCase(), this::loadColumnNames);
    }

    private List<String> loadColumnNames(String tableName) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?",
                    String.class, tableName);
        } catch (Exception e) {
            log.warn("Could not load column metadata for table '{}': {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Real distinct values of one column (e.g. every city actually in
     * `customers.city`: "Mumbai", "Pune", "Delhi", ...). Used as a
     * data-driven safety net — see class doc — never as a substitute for
     * real query filtering.
     *
     * WHY tableName/columnName are validated against the active schema before
     * being concatenated into SQL: JDBC placeholders parameterize VALUES,
     * not identifiers, so a table/column name can't go through a
     * PreparedStatement parameter. Every caller in this codebase only ever
     * passes its own hardcoded literals (never raw user input) — this check
     * is a defense-in-depth guard against that assumption quietly breaking
     * in the future, not a response to any current injection path.
     */
    public List<String> getDistinctValues(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return List.of();
        }
        String key = tableName.toLowerCase() + "." + columnName.toLowerCase();
        return distinctValueCache.computeIfAbsent(key, k -> loadDistinctValues(tableName, columnName));
    }

    private List<String> loadDistinctValues(String tableName, String columnName) {
        boolean recognized = activeSchema.findTable(tableName)
                .map(table -> table.columns().stream().anyMatch(col -> col.equalsIgnoreCase(columnName)))
                .orElse(false);
        if (!recognized) {
            log.warn("Refused to load distinct values for unrecognized table/column: {}.{}", tableName, columnName);
            return List.of();
        }
        try {
            String sql = "SELECT DISTINCT " + columnName + " FROM " + tableName
                    + " WHERE " + columnName + " IS NOT NULL LIMIT 500";
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            log.warn("Could not load distinct values for {}.{}: {}", tableName, columnName, e.getMessage(), e);
            return List.of();
        }
    }
}
