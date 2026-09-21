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

/** Reads live column names and distinct column values from the database. */
@Component
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);

    private final ExplainExecutor explainExecutor;
    private final ActiveSchemaProvider activeSchema;
    private final Map<String, List<String>> columnCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();

    public SchemaMetadataService(ExplainExecutor explainExecutor, ActiveSchemaProvider activeSchema) {
        this.explainExecutor = explainExecutor;
        this.activeSchema = activeSchema;
    }

    void registerSchemaChangeListener() {
        activeSchema.onSchemaChanged(this::clearCaches);
    }

    public void clearCaches() {
        columnCache.clear();
        distinctValueCache.clear();
    }

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
            return explainExecutor.withActiveCatalog(jdbc -> jdbc.queryForList(sql, String.class));
        } catch (Exception e) {
            log.warn("Could not load distinct values for {}.{}: {}", tableName, columnName, e.getMessage(), e);
            return List.of();
        }
    }
}
