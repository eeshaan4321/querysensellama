package com.querysense.backend.service.impl;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.exception.LlmUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OllamaServiceTest {

    private RestTemplate restTemplate;
    private OllamaService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new OllamaService(restTemplate, new ActiveSchemaProvider());

        ReflectionTestUtils.setField(service, "apiUrl", "http://localhost:11434/api/generate");
        ReflectionTestUtils.setField(service, "model", "qwen2.5-coder:7b");
        ReflectionTestUtils.setField(service, "keepAlive", "30m");
        ReflectionTestUtils.setField(service, "numPredict", 300);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void modelReplies(String rawText) {
        ResponseEntity<Map> response = ResponseEntity.ok(Map.of("response", rawText));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("strips a ```sql fence — the exact behaviour observed from the real model")
    void stripsSqlFence() {
        modelReplies("""
                ```sql
                SELECT c.id, c.name FROM customers c WHERE c.city = 'Mumbai';
                ```""");

        String result = service.generateQuery("customers in mumbai", "MySQL");

        assertThat(result).isEqualTo("SELECT c.id, c.name FROM customers c WHERE c.city = 'Mumbai';");
    }

    @Test
    @DisplayName("strips fences carrying any language tag, not just 'sql'")
    void stripsNonSqlLanguageTags() {
        modelReplies("```mysql\nSELECT * FROM customers;\n```");
        assertThat(service.generateQuery("all customers", "MySQL"))
                .isEqualTo("SELECT * FROM customers;");

        modelReplies("```javascript\ndb.users.find({ city: \"Mumbai\" })\n```");
        assertThat(service.generateQuery("users in mumbai", "MongoDB"))
                .isEqualTo("db.users.find({ city: \"Mumbai\" })");
    }

    @Test
    @DisplayName("drops a conversational lead-in line")
    void dropsLeadInLine() {
        modelReplies("Here is the query:\nSELECT * FROM orders;");

        assertThat(service.generateQuery("all orders", "MySQL"))
                .isEqualTo("SELECT * FROM orders;");
    }

    @Test
    @DisplayName("drops trailing commentary the model added after the statement")
    void dropsTrailingCommentary() {
        modelReplies("""
                SELECT * FROM customers WHERE city = 'Pune';

                This query selects every customer located in Pune.""");

        assertThat(service.generateQuery("customers in pune", "MySQL"))
                .isEqualTo("SELECT * FROM customers WHERE city = 'Pune';");
    }

    @Test
    @DisplayName("keeps prose out but keeps the fenced query when the model does both")
    void prefersFencedContentOverSurroundingProse() {
        modelReplies("""
                Sure, here is what you asked for:
                ```sql
                SELECT COUNT(*) FROM orders WHERE status = 'shipped';
                ```
                Let me know if you need anything else.""");

        assertThat(service.generateQuery("how many shipped orders", "MySQL"))
                .isEqualTo("SELECT COUNT(*) FROM orders WHERE status = 'shipped';");
    }

    @Test
    @DisplayName("does NOT truncate MongoDB output at a semicolon")
    void doesNotApplySemicolonCutToMongo() {
        modelReplies("db.orders.aggregate([{ $match: { status: \"shipped\" } }])");

        assertThat(service.generateQuery("shipped orders", "MongoDB"))
                .isEqualTo("db.orders.aggregate([{ $match: { status: \"shipped\" } }])");
    }

    @Test
    @DisplayName("leaves already-clean output untouched")
    void leavesCleanOutputAlone() {
        modelReplies("SELECT * FROM customers;");

        assertThat(service.generateQuery("all customers", "MySQL"))
                .isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("an unreachable Ollama throws instead of returning a placeholder string")
    void unreachableOllamaThrows() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.generateQuery("customers in mumbai", "MySQL"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("Cannot reach Ollama");
    }

    @Test
    @DisplayName("a body with no 'response' field is reported, not returned as an empty query")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void missingResponseFieldThrows() {
        ResponseEntity<Map> empty = ResponseEntity.ok(Map.of("done", true));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(empty);

        assertThatThrownBy(() -> service.generateQuery("all customers", "MySQL"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("stream");
    }

    @Test
    @DisplayName("warmUp never propagates a failure — a down model must not break startup")
    void warmUpSwallowsFailures() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        service.warmUp();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String promptSent() {
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), request.capture(), eq(Map.class));
        return (String) ((Map<String, Object>) request.getValue().getBody()).get("prompt");
    }

    @Test
    @DisplayName("a MongoDB question gets the mongosh prompt and the Mongo schema, never the SQL rules")
    void mongoPrompt() {
        modelReplies("db.orders.find({\"status\": \"PAID\"})");

        service.generateQuery("paid orders", "MongoDB");

        String prompt = promptSent();
        assertThat(prompt)
                .contains("into one MongoDB shell (mongosh) query")
                .contains("find, aggregate, countDocuments, deleteMany")
                .contains("Real MongoDB schema")
                .contains("status (string, one of \"NEW\"")
                .contains("Natural language input: paid orders")
                .doesNotContain("GROUP BY")
                .doesNotContain("semicolon");
    }

    @Test
    @DisplayName("the MySQL prompt is unchanged")
    void mysqlPromptUnchanged() {
        modelReplies("SELECT 1;");

        service.generateQuery("all customers", "MySQL");

        assertThat(promptSent())
                .contains("into a valid MySQL query")
                .contains("End every SQL statement with a semicolon.")
                .contains("Real schema — use ONLY these tables and columns")
                .doesNotContain("mongosh");
    }

    @Test
    @DisplayName("a MongoDB reply with prose after the query is cut to the query")
    void mongoReplyCutToFirstStatement() {
        modelReplies("db.orders.find({\"status\": \"PAID\"})\n\nThis query finds every paid order.");

        assertThat(service.generateQuery("paid orders", "MongoDB"))
                .isEqualTo("db.orders.find({\"status\": \"PAID\"})");
    }

    @Test
    @DisplayName("a chained find, and a bracket inside a string, survive the cut")
    void chainAndQuotedParenSurvive() {
        modelReplies("Here is the query:\ndb.customers.find({\"name\": \"a)b\"}).sort({\"name\": 1}).limit(5); // top 5");

        assertThat(service.generateQuery("customers", "MongoDB"))
                .isEqualTo("db.customers.find({\"name\": \"a)b\"}).sort({\"name\": 1}).limit(5)");
    }
}
