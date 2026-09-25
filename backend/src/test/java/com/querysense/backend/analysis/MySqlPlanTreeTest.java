package com.querysense.backend.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querysense.backend.dto.PlanNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The plan tree recovered from EXPLAIN FORMAT=JSON — the nesting the tabular reader cannot express. */
class MySqlPlanTreeTest {

    private final MySqlJsonExplainParser parser = new MySqlJsonExplainParser(new ObjectMapper());

    private static final String SINGLE_TABLE = """
            {
              "query_block": {
                "select_id": 1,
                "cost_info": { "query_cost": "31.45" },
                "table": {
                  "table_name": "customers",
                  "access_type": "ref",
                  "key": "idx_city",
                  "rows_examined_per_scan": 2,
                  "cost_info": { "read_cost": "1.20", "prefix_cost": "2.45" }
                }
              }
            }
            """;

    private static final String JOIN_WITH_GROUPING = """
            {
              "query_block": {
                "select_id": 1,
                "cost_info": { "query_cost": "412.88" },
                "grouping_operation": {
                  "using_temporary_table": true,
                  "using_filesort": true,
                  "nested_loop": [
                    { "table": {
                        "table_name": "c",
                        "access_type": "ALL",
                        "rows_examined_per_scan": 303,
                        "cost_info": { "prefix_cost": "31.45" }
                    } },
                    { "table": {
                        "table_name": "o",
                        "access_type": "ref",
                        "key": "idx_customer_id",
                        "rows_examined_per_scan": 4,
                        "cost_info": { "prefix_cost": "381.43" }
                    } }
                  ]
                }
              }
            }
            """;

    @Test
    @DisplayName("a single-table plan is a query block with one table under it")
    void singleTableTree() {
        PlanNode root = parser.parsePlanTree(SINGLE_TABLE);

        assertThat(root).isNotNull();
        assertThat(root.kind()).isEqualTo("query_block");
        assertThat(root.cost()).isEqualTo(31.45);
        assertThat(root.children()).hasSize(1);

        PlanNode table = root.children().get(0);
        assertThat(table.kind()).isEqualTo("table");
        assertThat(table.label()).isEqualTo("customers");
        assertThat(table.accessType()).isEqualTo("ref");
        assertThat(table.keyUsed()).isEqualTo("idx_city");
        assertThat(table.estimatedRows()).isEqualTo(2L);
        assertThat(table.children()).isEmpty();
    }

    @Test
    @DisplayName("nesting is preserved — the grouping operation sits above the join, not beside it")
    void nestingIsPreserved() {
        PlanNode root = parser.parsePlanTree(JOIN_WITH_GROUPING);

        assertThat(root.kind()).isEqualTo("query_block");
        assertThat(root.children()).hasSize(1);

        PlanNode grouping = root.children().get(0);
        assertThat(grouping.kind()).isEqualTo("grouping_operation");
        assertThat(grouping.children()).hasSize(1);

        PlanNode join = grouping.children().get(0);
        assertThat(join.kind()).isEqualTo("nested_loop");
        assertThat(join.children()).hasSize(2);
    }

    @Test
    @DisplayName("join order is the order MySQL reports, because that is the plan")
    void joinOrderIsPreserved() {
        PlanNode join = parser.parsePlanTree(JOIN_WITH_GROUPING)
                .children().get(0).children().get(0);

        assertThat(join.children()).extracting(PlanNode::label).containsExactly("c", "o");
    }

    @Test
    @DisplayName("a wrapper operation carries its own flags, so the sort is attributed where it happens")
    void wrapperCarriesItsFlags() {
        PlanNode grouping = parser.parsePlanTree(JOIN_WITH_GROUPING).children().get(0);

        assertThat(grouping.usesFilesort()).isTrue();
        assertThat(grouping.usesTemporaryTable()).isTrue();
    }

    @Test
    @DisplayName("a table node carries its prefix cost, which is what MySQL attributes to reaching it")
    void tableCarriesPrefixCost() {
        PlanNode join = parser.parsePlanTree(JOIN_WITH_GROUPING)
                .children().get(0).children().get(0);

        assertThat(join.children().get(0).cost()).isEqualTo(31.45);
        assertThat(join.children().get(1).cost()).isEqualTo(381.43);
    }

    @Test
    @DisplayName("null and blank input give no tree rather than an exception")
    void emptyInputGivesNoTree() {
        assertThat(parser.parsePlanTree(null)).isNull();
        assertThat(parser.parsePlanTree("  ")).isNull();
    }

    @Test
    @DisplayName("unparseable JSON gives no tree rather than an exception, like the cost probe")
    void unparseableJsonGivesNoTree() {
        assertThat(parser.parsePlanTree("{ not json")).isNull();
    }

    @Test
    @DisplayName("JSON with no query_block gives no tree")
    void missingQueryBlockGivesNoTree() {
        assertThat(parser.parsePlanTree("{ \"something_else\": {} }")).isNull();
    }

    @Test
    @DisplayName("cost arrives as a JSON string and must not be read as zero")
    void costIsParsedFromText() {
        PlanNode root = parser.parsePlanTree(SINGLE_TABLE);

        assertThat(root.cost()).isNotNull().isNotEqualTo(0.0);
    }
}
