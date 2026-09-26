package com.querysense.backend.controller;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.HealthResponse;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness endpoints. The frontend's shell banner polls these. */
@RestController
public class HealthController {

    private final ActiveSchemaProvider activeSchema;

    public HealthController(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    @GetMapping("/api/health")
    public HealthResponse health() {
        SchemaSnapshot active = activeSchema.active(Dialect.MYSQL);
        return new HealthResponse("UP", active.label(), active.builtIn());
    }

    /** Kept because probes and docs predating /api/health call it. */
    @GetMapping("/test")
    public String test() {
        return "QuerySense Running";
    }
}
