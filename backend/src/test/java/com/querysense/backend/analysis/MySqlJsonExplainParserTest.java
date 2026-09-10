package com.querysense.backend.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querysense.backend.dto.TableAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySqlJsonExplainParserTest
 *
 * WHY THESE TESTS EXIST:
 * The JSON reader has to produce TableAnalysis objects indistinguishable
 * from the tabular reader's, or enabling the format would quietly change
 * what every downstream module sees. The interesting case is the one the
 * tabular format cannot express at all: in JSON, `using_filesort` and
 * `using_temporary_table` sit on the *operation* that owns them, not on
 * the table row, so they have to be inherited downwards. A flat field read
 * would silently drop both.
 *
 * The JSON documents below are the shape MySQL 8 actually emits.
 */
class MySqlJsonExplainParserTest {

    private final MySqlJsonExplainParser parser = new MySqlJsonExplainParser(new ObjectMapper());

    @Test
    @DisplayName("a single-table plan maps to one TableAnalysis")
    void singleTablePlan() {
        String json = """
                {
                  "query_block": {
                    "select_id": 1,
                    "table": {
                      "table_name": "customers",
                      "access_type": "ref",
                      "possible_keys": ["idx_city"],
                      "key": "idx_city",
                      "key_length": "203",
                      "ref": ["const"],
                      "rows_examined_per_scan": 2,
                      "filtered": "100.00"
                    }
                  }
                }
                """;

        List<TableAnalysis> tables = parser.parse(json);

        assertThat(tables).hasSize(1);
        TableAnalysis customers = tables.get(0);
        assertThat(customers.getTableName()).isEqualTo("customers");
        assertThat(customers.getAccessType()).isEqualTo("ref");
        assertThat(customers.getPossibleKeys()).isEqualTo("idx_city");
        assertThat(customers.getKeyUsed()).isEqualTo("idx_city");
        assertThat(customers.getKeyLength()).isEqualTo("203");
        assertThat(customers.getRef()).isEqualTo("const");
        assertThat(customers.getEstimatedRows()).isEqualTo(2L);
        assertThat(customers.isUsesIndex()).isTrue();
        assertThat(customers.isFullTableScan()).isFalse();
    }

    @Test
    @DisplayName("a nested_loop JOIN yields one entry per table, in plan order")
    void nestedLoopJoin() {
        List<TableAnalysis> tables = parser.parse(joinWithGroupingJson());

        assertThat(tables).hasSize(2);
        assertThat(tables.get(0).getTableName()).isEqualTo("c");
        assertThat(tables.get(0).getAccessType()).isEqualTo("ALL");
        assertThat(tables.get(0).isFullTableScan()).isTrue();
        assertThat(tables.get(0).getKeyUsed()).isNull();
        assertThat(tables.get(0).getEstimatedRows()).isEqualTo(303L);

        assertThat(tables.get(1).getTableName()).isEqualTo("o");
        assertThat(tables.get(1).getAccessType()).isEqualTo("ref");
        assertThat(tables.get(1).getKeyUsed()).isEqualTo("idx_customer_id");
        assertThat(tables.get(1).getRef()).isEqualTo("querysense.c.id");
    }

    @Test
    @DisplayName("filesort and temporary-table flags on a wrapper operation reach the tables under it")
    void wrapperOperationFlagsAreInherited() {
        List<TableAnalysis> tables = parser.parse(joinWithGroupingJson());

        // Neither table node carries these flags itself — they sit on
        // grouping_operation. A flat read would report both as false.
        assertThat(tables).allSatisfy(table -> {
            assertThat(table.isUsesFilesort()).isTrue();
            assertThat(table.isUsesTemporaryTable()).isTrue();
        });
        assertThat(tables.get(0).getExtra()).contains("Using temporary").contains("Using filesort");
    }

    @Test
    @DisplayName("a plan with no table nodes yields an empty list rather than failing")
    void planWithoutTables() {
        assertThat(parser.parse("{\"query_block\": {\"select_id\": 1}}")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("malformed JSON fails loudly so the caller can fall back to tabular")
    void malformedJsonThrows() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be parsed");
    }

    private String joinWithGroupingJson() {
        return """
                {
                  "query_block": {
                    "select_id": 1,
                    "grouping_operation": {
                      "using_temporary_table": true,
                      "using_filesort": true,
                      "nested_loop": [
                        { "table": {
                            "table_name": "c",
                            "access_type": "ALL",
                            "possible_keys": ["PRIMARY"],
                            "rows_examined_per_scan": 303
                        } },
                        { "table": {
                            "table_name": "o",
                            "access_type": "ref",
                            "possible_keys": ["idx_customer_id"],
                            "key": "idx_customer_id",
                            "key_length": "5",
                            "ref": ["querysense.c.id"],
                            "rows_examined_per_scan": 4,
                            "using_index": true
                        } }
                      ]
                    }
                  }
                }
                """;
    }
}
