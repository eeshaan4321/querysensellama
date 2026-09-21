package com.querysense.backend.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querysense.backend.dto.TableAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Parses EXPLAIN FORMAT=JSON output into per-table analysis and query cost. */
@Component
public class MySqlJsonExplainParser {

    private static final String TABLE_FIELD = "table";
    private static final String FILESORT_FIELD = "using_filesort";
    private static final String TEMP_TABLE_FIELD = "using_temporary_table";
    private static final String QUERY_BLOCK_FIELD = "query_block";
    private static final String COST_INFO_FIELD = "cost_info";
    private static final String QUERY_COST_FIELD = "query_cost";

    private final ObjectMapper objectMapper;

    public MySqlJsonExplainParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TableAnalysis> parse(String json) {
        List<TableAnalysis> tables = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return tables;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("EXPLAIN FORMAT=JSON output could not be parsed", e);
        }

        walk(root, false, false, tables);
        return tables;
    }

    public Double parseQueryCost(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }

        JsonNode cost = root.path(QUERY_BLOCK_FIELD).path(COST_INFO_FIELD).path(QUERY_COST_FIELD);
        if (cost.isMissingNode() || cost.isNull()) {
            return null;
        }
        if (cost.isNumber()) {
            return cost.asDouble();
        }
        try {
            return Double.valueOf(cost.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void walk(JsonNode node, boolean inheritedFilesort, boolean inheritedTemp,
                      List<TableAnalysis> out) {
        if (node == null || !node.isObject()) {
            return;
        }

        boolean filesortHere = inheritedFilesort || node.path(FILESORT_FIELD).asBoolean(false);
        boolean tempHere = inheritedTemp || node.path(TEMP_TABLE_FIELD).asBoolean(false);

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();

            if (TABLE_FIELD.equals(field.getKey()) && value.isObject()) {
                out.add(toTableAnalysis(value, filesortHere, tempHere));
                walk(value, filesortHere, tempHere, out);
            } else if (value.isObject()) {
                walk(value, filesortHere, tempHere, out);
            } else if (value.isArray()) {
                for (JsonNode element : value) {
                    walk(element, filesortHere, tempHere, out);
                }
            }
        }
    }

    private TableAnalysis toTableAnalysis(JsonNode tableNode, boolean inheritedFilesort,
                                          boolean inheritedTemp) {
        TableAnalysis analysis = new TableAnalysis();

        analysis.setTableName(text(tableNode, "table_name"));
        analysis.setAccessType(text(tableNode, "access_type"));
        analysis.setPossibleKeys(joinArray(tableNode.get("possible_keys")));
        analysis.setKeyUsed(text(tableNode, "key"));
        analysis.setKeyLength(text(tableNode, "key_length"));
        analysis.setRef(joinArray(tableNode.get("ref")));

        JsonNode rows = tableNode.hasNonNull("rows_examined_per_scan")
                ? tableNode.get("rows_examined_per_scan")
                : tableNode.get("rows");
        analysis.setEstimatedRows(rows != null && rows.isNumber() ? rows.asLong() : null);

        boolean filesort = inheritedFilesort || tableNode.path(FILESORT_FIELD).asBoolean(false);
        boolean temporary = inheritedTemp || tableNode.path(TEMP_TABLE_FIELD).asBoolean(false);
        boolean usingIndex = tableNode.path("using_index").asBoolean(false);

        analysis.setUsesFilesort(filesort);
        analysis.setUsesTemporaryTable(temporary);
        analysis.setFullTableScan("ALL".equalsIgnoreCase(analysis.getAccessType()));
        analysis.setUsesIndex(analysis.getKeyUsed() != null);
        analysis.setExtra(buildExtra(usingIndex, temporary, filesort,
                text(tableNode, "using_join_buffer")));

        return analysis;
    }

    private String buildExtra(boolean usingIndex, boolean temporary, boolean filesort,
                              String joinBuffer) {
        List<String> parts = new ArrayList<>();
        if (usingIndex) {
            parts.add("Using index");
        }
        if (temporary) {
            parts.add("Using temporary");
        }
        if (filesort) {
            parts.add("Using filesort");
        }
        if (joinBuffer != null) {
            parts.add("Using join buffer (" + joinBuffer + ")");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asText();
        return asText.isBlank() ? null : asText;
    }

    private String joinArray(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isNull() && !element.asText().isBlank()) {
                values.add(element.asText());
            }
        }
        return values.isEmpty() ? null : String.join(",", values);
    }
}
