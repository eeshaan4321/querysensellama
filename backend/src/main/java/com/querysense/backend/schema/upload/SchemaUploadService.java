package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.DdlSchemaParser;
import com.querysense.backend.schema.ddl.ParsedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Parses, validates, provisions, seeds and activates an uploaded schema. */
@Service
public class SchemaUploadService {

    private static final Logger log = LoggerFactory.getLogger(SchemaUploadService.class);

    private final DdlSchemaParser parser;
    private final SchemaUploadValidator validator;
    private final SchemaProvisioner provisioner;
    private final SchemaSeeder seeder;
    private final ActiveSchemaProvider activeSchema;
    private final Environment environment;

    private volatile List<String> lastWarnings = List.of();

    public SchemaUploadService(DdlSchemaParser parser,
                               SchemaUploadValidator validator,
                               SchemaProvisioner provisioner,
                               SchemaSeeder seeder,
                               ActiveSchemaProvider activeSchema,
                               Environment environment) {
        this.parser = parser;
        this.validator = validator;
        this.provisioner = provisioner;
        this.seeder = seeder;
        this.activeSchema = activeSchema;
        this.environment = environment;
    }

    public SchemaSnapshot upload(String ddl, String label) {
        requireMySqlProfile();

        if (ddl == null || ddl.isBlank()) {
            throw new SchemaUploadException(
                    "The uploaded schema was empty. Upload a .sql file containing CREATE TABLE statements.");
        }

        ParsedSchema parsed = parser.parse(ddl);
        validator.validate(parsed);
        this.lastWarnings = parsed.warnings();

        provisioner.provision(parsed);

        SchemaSeeder.SeedReport seeded = seeder.seed(parsed);
        Map<String, Integer> rowCounts = seeded.rowCounts();
        if (!seeded.warnings().isEmpty()) {
            List<String> combined = new ArrayList<>(this.lastWarnings);
            combined.addAll(seeded.warnings());
            this.lastWarnings = List.copyOf(combined);
        }

        SchemaSnapshot snapshot = new SchemaSnapshot(
                "upload-" + UUID.randomUUID().toString().substring(0, 8),
                label == null || label.isBlank() ? "Uploaded schema" : label,
                provisioner.uploadCatalog(),
                parsed.toKnownTables(),
                parsed.allForeignKeys(),
                rowCounts,
                Instant.now());

        log.info("Uploaded schema '{}': {} tables, {} rows total",
                snapshot.label(), snapshot.tables().size(),
                rowCounts.values().stream().mapToInt(Integer::intValue).sum());

        return activeSchema.publish(snapshot);
    }

    public SchemaSnapshot reset() {
        this.lastWarnings = List.of();
        return activeSchema.resetToBuiltIn();
    }

    public List<String> lastWarnings() {
        return lastWarnings;
    }

    private void requireMySqlProfile() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("h2")) {
            throw new SchemaUploadException(
                    "Schema upload needs MySQL and the backend is running under the H2 profile. "
                            + "H2 cannot create a separate catalog or produce MySQL EXPLAIN output, so an "
                            + "uploaded schema could not be analysed. Restart without "
                            + "-Dspring-boot.run.profiles=h2.");
        }
    }
}
