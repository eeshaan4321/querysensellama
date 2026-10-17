package com.querysense.backend.repository;

import com.querysense.backend.model.QueryHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CacheLookupRepositoryTest {

    @Autowired
    private QueryHistoryRepository repository;

    private QueryHistory row(String input, String label, String source, String sql, LocalDateTime at) {
        QueryHistory history = new QueryHistory();
        history.setUserInput(input);
        history.setGeneratedQuery(sql);
        history.setDatabaseType("MySQL");
        history.setSource(source);
        history.setConfidence(0.65);
        history.setSchemaLabel(label);
        history.setCreatedAt(at);
        return repository.save(history);
    }

    @Test
    @DisplayName("an older rule-engine row does not hide the LLM's answer to the same question")
    void olderRuleRowDoesNotHideLlmAnswer() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 9, 0);
        row("how many employees in each department", "demo", "RULE_ENGINE", "SELECT 1;", t);
        row("how many employees in each department", "demo", "OLLAMA", "SELECT 2;", t.plusDays(1));
        row("how many employees in each department", "demo", "OLLAMA", "SELECT 3;", t.plusDays(2));

        assertThat(repository
                .findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
                        "How many employees in each department", "mysql", "demo", "OLLAMA"))
                .get().extracting(QueryHistory::getGeneratedQuery).isEqualTo("SELECT 3;");
    }

    @Test
    @DisplayName("an LLM answer under another schema is not returned")
    void otherSchemaIsIgnored() {
        row("show orders", "other.sql", "OLLAMA", "SELECT 9;", LocalDateTime.of(2026, 10, 1, 9, 0));

        assertThat(repository
                .findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelAndSourceOrderByCreatedAtDescIdDesc(
                        "show orders", "MySQL", "demo", "OLLAMA"))
                .isEmpty();
    }
}
