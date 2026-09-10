package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBuildResultTest {

    @Test
    @DisplayName("a built result carries its SQL and a BUILT trace")
    void builtCarriesSqlAndTrace() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        RuleBuildResult result = RuleBuildResult.built("SELECT * FROM customers;", trace);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM customers;");
        assertThat(result.trace().outcome()).isEqualTo("BUILT");
    }

    @Test
    @DisplayName("a declined result has no SQL and keeps the reason plus whatever bound")
    void declinedKeepsReasonAndPartialBindings() {
        RuleBuildResult result = RuleBuildResult.declined(
                "The question mentions a date range, which this engine does not build.",
                List.of(new RuleTrace.Binding("orders", "TABLE", "orders")));

        assertThat(result.built()).isFalse();
        assertThat(result.sql()).isNull();
        assertThat(result.trace().outcome()).isEqualTo("DECLINED");
        assertThat(result.trace().declineReason()).contains("date range");
        assertThat(result.trace().bindings()).hasSize(1);
    }

    @Test
    @DisplayName("toOptional bridges to the legacy tryBuild signature")
    void toOptionalBridgesLegacyCallers() {
        assertThat(RuleBuildResult.declined("no", List.of()).toOptional()).isEmpty();
        assertThat(RuleBuildResult.built("SELECT 1;", null).toOptional()).contains("SELECT 1;");
    }
}
