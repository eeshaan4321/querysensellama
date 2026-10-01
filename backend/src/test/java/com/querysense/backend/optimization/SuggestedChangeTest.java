package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.enums.SuggestionSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestedChangeTest {

    private final TransactionalOptimizationRules rules = new TransactionalOptimizationRules();
    private final ResourceConsciousService resources = new ResourceConsciousService();

    private static QueryAnalysisResult mysql() {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MySQL");
        result.setTableName("orders");
        result.setEstimatedRows(6_000L);
        return result;
    }

    @Test
    @DisplayName("TX1 carries the index on the filtered column, and its message is unchanged")
    void tx1() {
        QueryAnalysisResult result = mysql();
        result.setHasFullTableScan(true);
        result.setFilteredColumn("status");

        OptimizationSuggestion tx1 = rules.evaluate(result).get(0);

        assertThat(tx1.getSuggestedChange()).isEqualTo("CREATE INDEX idx_orders_status ON orders(status);");
        assertThat(tx1.getMessage()).isEqualTo("Full table scan on 'orders' in a transactional query. This query "
                + "path is expected to run frequently — a missing index here means this scan repeats on every "
                + "call. Add an index on the lookup column(s) immediately.");
    }

    @Test
    @DisplayName("TX1 without a known filtered column suggests no statement")
    void tx1WithoutColumn() {
        QueryAnalysisResult result = mysql();
        result.setHasFullTableScan(true);

        assertThat(rules.evaluate(result).get(0).getSuggestedChange()).isNull();
    }

    @Test
    @DisplayName("TX2 carries the index on the sort column")
    void tx2() {
        QueryAnalysisResult result = mysql();
        result.setUsesFilesort(true);
        result.setUsesIndex(true);
        result.setKeyUsed("idx_status");
        result.setOrderByColumn("order_date");

        assertThat(rules.evaluate(result)).anySatisfy(s -> assertThat(s.getSuggestedChange())
                .isEqualTo("CREATE INDEX idx_orders_order_date ON orders(order_date);"));
    }

    @Test
    @DisplayName("TX3 has no index fix, so no statement")
    void tx3() {
        QueryAnalysisResult result = mysql();
        result.setUsesTemporaryTable(true);
        result.setUsesIndex(true);
        result.setKeyUsed("idx_status");

        assertThat(rules.evaluate(result)).allSatisfy(s -> assertThat(s.getSuggestedChange()).isNull());
    }

    @Test
    @DisplayName("RS1 and RS3 carry the sort and filter indexes")
    void resourceRules() {
        QueryAnalysisResult result = mysql();
        result.setUsesFilesort(true);
        result.setOrderByColumn("order_date");
        result.setHasFullTableScan(true);
        result.setFilteredColumn("status");

        List<OptimizationSuggestion> low = resources.evaluate(ResourceLevel.LOW, result);

        assertThat(low).extracting(OptimizationSuggestion::getSuggestedChange).containsExactly(
                "CREATE INDEX idx_orders_order_date ON orders(order_date);",
                "CREATE INDEX idx_orders_status ON orders(status);");
    }

    @Test
    @DisplayName("shifting severity keeps the suggested change and the source")
    void severityShiftKeepsChange() {
        OptimizationSuggestion original = OptimizationSuggestion.domain("m", SuggestionSeverity.WARNING, "CREATE INDEX x");

        OptimizationSuggestion shifted = resources.adjustSeverity(ResourceLevel.LOW, List.of(original)).get(0);

        assertThat(shifted.getSeverity()).isEqualTo(SuggestionSeverity.CRITICAL);
        assertThat(shifted.getSuggestedChange()).isEqualTo("CREATE INDEX x");
        assertThat(shifted.getSource()).isEqualTo(OptimizationSuggestion.Source.DOMAIN);
    }
}
