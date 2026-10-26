package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Requests the engine refuses outright, so no other layer is asked to write them. */
class RefusedRequestsTest {

    private QueryIntentParser parser;

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("orders"), eq("status"))).thenReturn(List.of("SHIPPED", "PENDING"));
        return m;
    }

    @BeforeEach
    void setUp() {
        parser = TestParsers.parser(new ActiveSchemaProvider(), metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    @ParameterizedTest
    @DisplayName("dropOrTruncateIsRefused: dropping or emptying a table is refused, known table or not")
    @ValueSource(strings = {"drop table orders", "drop the orders table", "please drop orders",
            "truncate orders", "drop table enrollments"})
    void dropOrTruncateIsRefused(String question) {
        for (Dialect dialect : Dialect.values()) {
            RuleBuildResult result = parser.parse(question, dialect);
            assertThat(result.refused()).as("%s on %s: %s", question, dialect, result.trace().declineReason()).isTrue();
            assertThat(result.trace().outcome()).isEqualTo(RuleTrace.REFUSED);
        }
    }

    @ParameterizedTest
    @DisplayName("deleteEverythingIsRefused: a delete that names no rows is refused")
    @EnumSource(Dialect.class)
    void deleteEverythingIsRefused(Dialect dialect) {
        RuleBuildResult result = parser.parse("delete all orders", dialect);
        assertThat(result.refused()).as(result.trace().declineReason()).isTrue();
        assertThat(result.trace().declineReason()).contains("which ones");
    }

    @Test
    @DisplayName("filteredDeleteStillBuilds: a delete that says which rows is built as before")
    void filteredDeleteStillBuilds() {
        RuleBuildResult result = parser.parse("delete orders whose status is pending", Dialect.MYSQL);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        assertThat(result.refused()).isFalse();
    }

    @ParameterizedTest
    @DisplayName("dropAsAWordIsNotRefused: 'drop' that isn't the opening command is not a refusal")
    @ValueSource(strings = {"orders with status dropped", "show the drop rate of orders"})
    void dropAsAWordIsNotRefused(String question) {
        assertThat(parser.parse(question, Dialect.MYSQL).refused()).isFalse();
    }
}
