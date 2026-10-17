package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/** Reads live column names and distinct column values from the database. */
@Component
public class SchemaMetadataService implements SchemaMetadata {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);

    private final ExplainExecutor explainExecutor;
    private final ActiveSchemaProvider activeSchema;
    private final Map<String, List<String>> columnCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();
    private final Map<String, OptionalDouble> shareCache = new ConcurrentHashMap<>();

    public SchemaMetadataService(ExplainExecutor explainExecutor, ActiveSchemaProvider activeSchema) {
        this.explainExecutor = explainExecutor;
        this.activeSchema = activeSchema;
        activeSchema.onSchemaChanged(dialect -> {
            if (dialect == Dialect.MYSQL) {
                clearCaches();
            }
        });
    }

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    public void clearCaches() {
        columnCache.clear();
        distinctValueCache.clear();
        shareCache.clear();
    }

    @Override
    public List<String> getColumnNames(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        return columnCache.computeIfAbsent(tableName.toLowerCase(), this::loadColumnNames);
    }

    private List<String> loadColumnNames(String tableName) {
        try {
            return explainExecutor.withActiveCatalog(jdbc -> jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?",
                    String.class, tableName));
        } catch (Exception e) {
            log.warn("Could not load column metadata for table '{}': {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<String> getDistinctValues(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return List.of();
        }
        String key = tableName.toLowerCase() + "." + columnName.toLowerCase();
        return distinctValueCache.computeIfAbsent(key, k -> loadDistinctValues(tableName, columnName));
    }

    private List<String> loadDistinctValues(String tableName, String columnName) {
        if (!recognized(tableName, columnName)) {
            log.warn("Refused to load distinct values for unrecognized table/column: {}.{}", tableName, columnName);
            return List.of();
        }
        try {
            String sql = "SELECT DISTINCT " + columnName + " FROM " + tableName
                    + " WHERE " + columnName + " IS NOT NULL LIMIT 500";
            return explainExecutor.withActiveCatalog(jdbc -> jdbc.queryForList(sql, String.class));
        } catch (Exception e) {
            log.warn("Could not load distinct values for {}.{}: {}", tableName, columnName, e.getMessage(), e);
            return List.of();
        }
    }

    private boolean recognized(String tableName, String columnName) {
        return activeSchema.findTable(Dialect.MYSQL, tableName)
                .map(table -> table.columns().stream().anyMatch(col -> col.equalsIgnoreCase(columnName)))
                .orElse(false);
    }

    @Override
    public OptionalDouble distinctShare(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return OptionalDouble.empty();
        }
        String key = tableName.toLowerCase() + "." + columnName.toLowerCase();
        OptionalDouble cached = shareCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!recognized(tableName, columnName)) {
            shareCache.put(key, OptionalDouble.empty());
            return OptionalDouble.empty();
        }
        try {
            String sql = "SELECT COUNT(DISTINCT " + columnName + "), COUNT(*) FROM " + tableName;
            long[] counts = explainExecutor.withActiveCatalog(jdbc -> jdbc.queryForObject(sql,
                    (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}));
            OptionalDouble share = counts == null || counts[1] == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) counts[0] / counts[1]);
            shareCache.put(key, share);
            return share;
        } catch (Exception e) {
            log.warn("Could not count distinct values for {}.{}: {}", tableName, columnName, e.getMessage());
            return OptionalDouble.empty();
        }
    }
}
