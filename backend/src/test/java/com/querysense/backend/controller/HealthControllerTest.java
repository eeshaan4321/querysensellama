package com.querysense.backend.controller;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.HealthResponse;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private HealthController controllerWith(ActiveSchemaProvider schema) {
        return new HealthController(schema);
    }

    @Test
    @DisplayName("the probe reports up, so a caller can branch on a field not a string")
    void reportsUp() {
        ActiveSchemaProvider schema = mock(ActiveSchemaProvider.class);
        when(schema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        HealthResponse response = controllerWith(schema).health();

        assertThat(response.getStatus()).isEqualTo("UP");
    }

    @Test
    @DisplayName("the live schema is named, so a probe says which schema is answering")
    void namesActiveSchema() {
        ActiveSchemaProvider schema = mock(ActiveSchemaProvider.class);
        when(schema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        HealthResponse response = controllerWith(schema).health();

        assertThat(response.getSchemaLabel()).isEqualTo(KnownSchema.builtIn().label());
        assertThat(response.isBuiltInSchema()).isTrue();
    }

    @Test
    @DisplayName("the legacy string endpoint still answers, so old probes do not break")
    void legacyStringEndpointSurvives() {
        ActiveSchemaProvider schema = mock(ActiveSchemaProvider.class);
        when(schema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        assertThat(controllerWith(schema).test()).isEqualTo("QuerySense Running");
    }
}
