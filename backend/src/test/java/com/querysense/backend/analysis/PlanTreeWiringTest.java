package com.querysense.backend.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The plan tree must reach the analysis result, and must cost no extra round trip. */
class PlanTreeWiringTest {

    private static final String PLAN_JSON = """
            {
              "query_block": {
                "select_id": 1,
                "cost_info": { "query_cost": "31.45" },
                "ordering_operation": {
                  "using_filesort": true,
                  "table": {
                    "table_name": "customers",
                    "access_type": "ALL",
                    "rows_examined_per_scan": 303
                  }
                }
              }
            }
            """;

    private JdbcTemplate jdbcTemplate;
    private MySqlExplainAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ExplainExecutor passThrough =
                new ExplainExecutor(jdbcTemplate, new ActiveSchemaProvider()) {
                    @Override
                    public <T> T withActiveCatalog(Function<JdbcTemplate, T> work) {
                        return work.apply(jdbcTemplate);
                    }
                };
        analyzer = new MySqlExplainAnalyzer(
                passThrough, new MySqlJsonExplainParser(new ObjectMapper()), "TABULAR");

        TableAnalysis customers = new TableAnalysis();
        customers.setTableName("customers");
        customers.setAccessType("ALL");
        customers.setEstimatedRows(303L);
        customers.setFullTableScan(true);
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TableAnalysis>>any()))
                .thenReturn(List.of(customers));
    }

    @Test
    @DisplayName("the plan tree reaches the analysis result")
    void planTreeReachesTheResult() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(PLAN_JSON);

        QueryAnalysisResult result = analyzer.analyze("SELECT * FROM customers ORDER BY name;", "customers");

        assertThat(result.getPlanTree()).isNotNull();
        assertThat(result.getPlanTree().kind()).isEqualTo("query_block");
        assertThat(result.getPlanTree().children().get(0).kind()).isEqualTo("ordering_operation");
    }

    @Test
    @DisplayName("the tree and the cost come from ONE probe, not two")
    void treeAndCostShareOneProbe() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(PLAN_JSON);

        QueryAnalysisResult result = analyzer.analyze("SELECT * FROM customers ORDER BY name;", "customers");

        assertThat(result.getQueryCost()).isEqualTo(31.45);
        assertThat(result.getPlanTree()).isNotNull();
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(String.class));
    }

    @Test
    @DisplayName("a failed probe costs the tree and the cost, never the plan")
    void failedProbeLeavesThePlanIntact() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("no JSON here"));

        QueryAnalysisResult result = analyzer.analyze("SELECT * FROM customers ORDER BY name;", "customers");

        assertThat(result.getPlanTree()).isNull();
        assertThat(result.getQueryCost()).isNull();
        assertThat(result.isExplainFailed())
                .as("the tabular plan succeeded, so the analysis did not fail")
                .isFalse();
        assertThat(result.getTableName()).isEqualTo("customers");
    }
}
