package com.querysense.backend.controller;

import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.HealthResponse;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import org.bson.Document;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness endpoints. The frontend's shell banner polls these. */
@RestController
public class HealthController {

    private final ActiveSchemaProvider activeSchema;
    private final MongoClient mongoClient;
    private final Environment environment;

    public HealthController(ActiveSchemaProvider activeSchema, MongoClient mongoClient, Environment environment) {
        this.activeSchema = activeSchema;
        this.mongoClient = mongoClient;
        this.environment = environment;
    }

    @GetMapping("/api/health")
    public HealthResponse health() {
        SchemaSnapshot active = activeSchema.active(Dialect.MYSQL);
        return new HealthResponse("UP", active.label(), active.builtIn(), mongoStatus());
    }

    private String mongoStatus() {
        String uri = environment.getProperty("spring.data.mongodb.uri");
        if (uri == null || uri.isBlank()) {
            return "NOT_CONFIGURED";
        }
        try {
            mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }

    /** Kept because probes and docs predating /api/health call it. */
    @GetMapping("/test")
    public String test() {
        return "QuerySense Running";
    }
}
