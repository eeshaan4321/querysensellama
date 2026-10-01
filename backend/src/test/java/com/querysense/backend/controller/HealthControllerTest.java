package com.querysense.backend.controller;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.HealthResponse;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private ActiveSchemaProvider schema;
    private MongoClient mongo;
    private MongoDatabase admin;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        schema = mock(ActiveSchemaProvider.class);
        when(schema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());
        mongo = mock(MongoClient.class);
        admin = mock(MongoDatabase.class);
        when(mongo.getDatabase("admin")).thenReturn(admin);
        when(admin.runCommand(any(Bson.class))).thenReturn(new Document("ok", 1.0));
        environment = new MockEnvironment().withProperty("spring.data.mongodb.uri", "mongodb://localhost:27017/querysense");
    }

    private HealthController controller() {
        return new HealthController(schema, mongo, environment);
    }

    @Test
    @DisplayName("the probe reports up, so a caller can branch on a field not a string")
    void reportsUp() {
        assertThat(controller().health().getStatus()).isEqualTo("UP");
    }

    @Test
    @DisplayName("the live schema is named, so a probe says which schema is answering")
    void namesActiveSchema() {
        HealthResponse response = controller().health();

        assertThat(response.getSchemaLabel()).isEqualTo(KnownSchema.builtIn().label());
        assertThat(response.isBuiltInSchema()).isTrue();
    }

    @Test
    @DisplayName("the legacy string endpoint still answers, so old probes do not break")
    void legacyStringEndpointSurvives() {
        assertThat(controller().test()).isEqualTo("QuerySense Running");
    }

    @Test
    @DisplayName("MongoDB answering a ping is UP")
    void mongoUp() {
        assertThat(controller().health().getMongo()).isEqualTo("UP");
    }

    @Test
    @DisplayName("MongoDB not answering is DOWN, and the backend itself still reports UP")
    void mongoDown() {
        when(admin.runCommand(any(Bson.class))).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        HealthResponse response = controller().health();

        assertThat(response.getMongo()).isEqualTo("DOWN");
        assertThat(response.getStatus()).isEqualTo("UP");
    }

    @Test
    @DisplayName("no MongoDB URI is NOT_CONFIGURED, and MongoDB is never contacted")
    void mongoNotConfigured() {
        environment.setProperty("spring.data.mongodb.uri", "");

        assertThat(controller().health().getMongo()).isEqualTo("NOT_CONFIGURED");
        verifyNoInteractions(mongo);
    }
}
