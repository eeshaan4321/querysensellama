package com.querysense.backend.service.impl;

import com.querysense.backend.exception.LlmUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OllamaServiceTest
 *
 * WHY THESE TESTS EXIST (and why they are about output cleaning, not about
 * whether the model is any good):
 * Model quality can only be judged by running the real model, which needs a
 * GPU and a 4.7GB download and is therefore not a unit test. What CAN and
 * should be pinned down here is everything that happens to the model's reply
 * on the way back — because that is the code most likely to break silently,
 * and because a real generation run against qwen2.5-coder:7b confirmed the
 * failure mode it defends against actually occurs: asked three separate times
 * in the prompt to emit no markdown, the model wrapped its (otherwise
 * correct) SQL in a ```sql fence anyway.
 *
 * The old Gemini implementation stripped exactly "```sql" and "```". These
 * cases cover what a smaller local model does instead.
 *
 * RestTemplate is mocked rather than hitting a live Ollama, so these run in
 * CI, on a machine with no GPU, and with the network unplugged.
 */
class OllamaServiceTest {

    private RestTemplate restTemplate;
    private OllamaService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new OllamaService(restTemplate);

        // @Value fields aren't populated outside a Spring context.
        ReflectionTestUtils.setField(service, "apiUrl", "http://localhost:11434/api/generate");
        ReflectionTestUtils.setField(service, "model", "qwen2.5-coder:7b");
        ReflectionTestUtils.setField(service, "keepAlive", "30m");
        ReflectionTestUtils.setField(service, "numPredict", 300);
    }

    /** Makes the mocked RestTemplate answer with whatever raw text a model might produce. */
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
        // A Mongo expression has no statement terminator, so cutting at the
        // first ';' would corrupt any pipeline that happens to contain one.
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
        // The regression this guards: the previous implementation RETURNED
        // "-- Gemini unavailable, please try again shortly." on failure, which
        // was then persisted to query_history and served back from the cache
        // as if it were a real generated query. It must throw.
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
        // This is what an accidental "stream": true looks like from here.
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

        service.warmUp(); // must not throw
    }
}
