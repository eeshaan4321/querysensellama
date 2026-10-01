package com.querysense.backend.service.impl;

import com.querysense.backend.analysis.PlanAnalyzer;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryAnalysisServiceImplTest {

    private record Stub(Dialect dialect, String label) implements PlanAnalyzer {
        @Override
        public QueryAnalysisResult analyze(String query) {
            QueryAnalysisResult result = new QueryAnalysisResult();
            result.setDatabaseType(label);
            result.setTableName(query);
            return result;
        }
    }

    private final QueryAnalysisServiceImpl service = new QueryAnalysisServiceImpl(List.of(
            new Stub(Dialect.MYSQL, "mysql"), new Stub(Dialect.MONGODB, "mongo")));

    @Test
    @DisplayName("each database type reaches its own analyzer, whatever its case")
    void routesByDialect() {
        assertThat(service.analyze("q1", "MySQL").getDatabaseType()).isEqualTo("mysql");
        assertThat(service.analyze("q2", "MongoDB").getDatabaseType()).isEqualTo("mongo");
        assertThat(service.analyze("q3", "mongodb").getTableName()).isEqualTo("q3");
    }

    @Test
    @DisplayName("an unknown database type is refused, which the API returns as a 400")
    void unknownTypeRefused() {
        assertThatThrownBy(() -> service.analyze("q", "Oracle")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a dialect with no analyzer fails at startup")
    void missingDialectFailsFast() {
        assertThatThrownBy(() -> new QueryAnalysisServiceImpl(List.of(new Stub(Dialect.MYSQL, "mysql"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
