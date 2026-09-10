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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SchemaUploadService
 *
 * The pipeline, in the only order that is safe: parse, validate, provision,
 * seed, publish. Nothing reaches the database until validation has passed,
 * and nothing becomes the active schema until its tables exist and hold
 * rows. A failure at any step leaves the previously active schema in place.
 *
 * WHY THE H2 CHECK COMES FIRST, BEFORE EVEN PARSING:
 * H2 has no equivalent of MySQL's CREATE DATABASE and catalog semantics, so
 * an upload under that profile would provision partially and then leave the
 * app pointed at a schema whose tables do not exist. PROJECT.md already
 * records the h2 profile as the most confusing failure mode in this project
 * — it boots fine and makes analysis fail with a message that blames the
 * user's query. Refusing outright, with a message that names the cause, is
 * the whole mitigation.
 */
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
        Map<String, Integer> rowCounts = seeder.seed(parsed);

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

    /** Statements the parser skipped during the most recent upload. */
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
