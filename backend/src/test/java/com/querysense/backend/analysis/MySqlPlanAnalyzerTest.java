package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MySqlPlanAnalyzerTest {

    private MySqlExplainAnalyzer explainAnalyzer;
    private SqlPreflightValidator preflight;
    private MySqlPlanAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        explainAnalyzer = mock(MySqlExplainAnalyzer.class);
        preflight = mock(SqlPreflightValidator.class);
        when(preflight.validate(anyString(), anyString())).thenReturn(Optional.empty());
        analyzer = new MySqlPlanAnalyzer(new SqlStructureParser(), explainAnalyzer, preflight, new VerdictService(1000L));
    }

    @Test
    @DisplayName("serves MySQL")
    void servesMySql() {
        assertThat(analyzer.dialect()).isEqualTo(Dialect.MYSQL);
    }

    @Test
    @DisplayName("a statement that is not a SELECT returns its structure with no verdict and no EXPLAIN")
    void nonSelectIsStructuralOnly() {
        QueryAnalysisResult result = analyzer.analyze("DELETE FROM customers WHERE id = 1");

        assertThat(result.getVerdict()).isNull();
        verifyNoInteractions(explainAnalyzer);
    }

    @Test
    @DisplayName("a preflight refusal is a failed analysis with an UNKNOWN verdict")
    void preflightRefusal() {
        when(preflight.validate(anyString(), anyString())).thenReturn(Optional.of("Column 'age' does not exist"));

        QueryAnalysisResult result = analyzer.analyze("SELECT age FROM customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).contains("Column 'age' does not exist");
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.UNKNOWN);
        verifyNoInteractions(explainAnalyzer);
    }

    @Test
    @DisplayName("a SELECT is explained, and the structural columns are merged in")
    void selectIsExplained() {
        QueryAnalysisResult explained = new QueryAnalysisResult();
        explained.setDatabaseType("MySQL");
        when(explainAnalyzer.analyze(anyString(), anyString())).thenReturn(explained);

        QueryAnalysisResult result = analyzer.analyze("SELECT * FROM customers WHERE city = 'Pune' ORDER BY name");

        assertThat(result).isSameAs(explained);
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getOrderByColumn()).isEqualTo("name");
        assertThat(result.getVerdict()).isNotNull();
    }
}
