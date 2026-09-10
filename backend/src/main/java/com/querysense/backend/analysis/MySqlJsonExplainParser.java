package com.querysense.backend.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querysense.backend.dto.TableAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MySqlJsonExplainParser
 *
 * WHY THIS CLASS EXISTS:
 * `EXPLAIN FORMAT=JSON` is the structured form of the same plan the tabular
 * EXPLAIN prints. It is strictly richer — it nests, so a filesort or a
 * temporary table is attached to the operation that owns it rather than
 * smeared across an `Extra` string, and it carries cost estimates the
 * tabular form omits entirely. That makes it the right target for future
 * work on this module.
 *
 * WHY IT IS OPT-IN AND NOT THE DEFAULT:
 * The tabular reader is the implementation that has actually been run
 * against this project's database. Swapping the default would put an
 * unexercised code path in front of every analysis for no immediate gain,
 * so the format is chosen by `querysense.explain.format` and defaults to
 * TABULAR. MySqlExplainAnalyzer falls back to tabular if JSON parsing
 * yields nothing, so enabling it cannot leave a caller with no plan at all.
 *
 * WHAT IT PRODUCES:
 * Exactly the same List&lt;TableAnalysis&gt; the tabular reader produces, so
 * everything downstream — the query-level summary, the warnings, the
 * QueryAnalysisResult contract — is identical either way. This class knows
 * about JSON; nothing else in the codebase has to.
 *
 * SHAPE IT WALKS:
 * <pre>
 * { "query_block": {
 *     "grouping_operation": { "using_temporary_table": true, "using_filesort": true,
 *       "nested_loop": [ { "table": {...} }, { "table": {...} } ] } } }
 * </pre>
 * `using_filesort` / `using_temporary_table` can sit on a wrapper operation
 * (ordering_operation, grouping_operation) rather than on the table itself,
 * so those flags are inherited downwards onto the tables they cover — that
 * is the whole reason the walk is recursive rather than a flat field read.
 */
@Component
public class MySqlJsonExplainParser {

    private static final String TABLE_FIELD = "table";
    private static final String FILESORT_FIELD = "using_filesort";
    private static final String TEMP_TABLE_FIELD = "using_temporary_table";

    private final ObjectMapper objectMapper;

    public MySqlJsonExplainParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses the single JSON document EXPLAIN FORMAT=JSON returns.
     *
     * @param json the raw JSON string from MySQL
     * @return one TableAnalysis per table node, in the order encountered;
     *         an empty list if the document contains no table nodes
     * @throws com.fasterxml.jackson.core.JsonProcessingException never thrown
     *         to callers — malformed JSON surfaces as a RuntimeException that
     *         MySqlExplainAnalyzer treats as "fall back to tabular"
     */
    public List<TableAnalysis> parse(String json) {
        List<TableAnalysis> tables = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return tables;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            // Deliberately unchecked: the caller's contract is "give me tables
            // or fail and I'll use tabular", not "handle Jackson's exceptions".
            throw new IllegalStateException("EXPLAIN FORMAT=JSON output could not be parsed", e);
        }

        walk(root, false, false, tables);
        return tables;
    }

    /**
     * Depth-first walk collecting every `table` node.
     *
     * @param inheritedFilesort true if an enclosing operation reported a filesort
     * @param inheritedTemp     true if an enclosing operation reported a temporary table
     */
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
                // A table node can itself contain a materialised subquery with
                // more tables under it, so keep descending.
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

    /**
     * Rebuilds a tabular-style `Extra` string from the JSON flags, so a
     * TableAnalysis reads the same whichever format produced it. Ordered and
     * separated the way MySQL's own Extra column is.
     */
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

    /** MySQL renders possible_keys and ref as arrays in JSON, as a comma list in tabular. */
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
