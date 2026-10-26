package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;
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
import java.util.concurrent.ConcurrentHashMap;

/** Parses, validates, provisions, seeds and activates an uploaded schema. */
@Service
public class SchemaUploadService {

    private static final Logger log = LoggerFactory.getLogger(SchemaUploadService.class);

    private final DialectRegistry<SchemaUploadParser> parsers;
    private final SchemaUploadValidator validator;
    private final DialectRegistry<SchemaProvisioning> provisioners;
    private final DialectRegistry<SchemaSeeding> seeders;
    private final ActiveSchemaProvider activeSchema;
    private final Environment environment;
    private final Map<Dialect, List<String>> lastWarnings = new ConcurrentHashMap<>();

    public SchemaUploadService(List<SchemaUploadParser> parsers,
                               SchemaUploadValidator validator,
                               List<SchemaProvisioning> provisioners,
                               List<SchemaSeeding> seeders,
                               ActiveSchemaProvider activeSchema,
                               Environment environment) {
        this.parsers = new DialectRegistry<>(parsers, "schema upload parser");
        this.validator = validator;
        this.provisioners = new DialectRegistry<>(provisioners, "schema provisioner");
        this.seeders = new DialectRegistry<>(seeders, "schema seeder");
        this.activeSchema = activeSchema;
        this.environment = environment;
    }

    public SchemaSnapshot upload(String text, String label) {
        return upload(text, label, Dialect.MYSQL);
    }

    public SchemaSnapshot upload(String text, String label, Dialect dialect) {
        if (dialect == Dialect.MONGODB) {
            requireMongoUri();
        } else {
            requireMySqlProfile();
        }

        if (text == null || text.isBlank()) {
            throw new SchemaUploadException(dialect == Dialect.MONGODB
                    ? "The uploaded schema was empty. Upload a .json file describing the collections, each with "
                            + "a $jsonSchema validator."
                    : "The uploaded schema was empty. Upload a .sql file containing CREATE TABLE statements.");
        }

        ParsedSchema parsed = parsers.get(dialect).parse(text);
        validator.validate(parsed, dialect);
        SchemaAliases.Cleaned aliases = SchemaAliases.clean(parsed.toKnownTables(), parsed.aliases());
        List<String> uploadWarnings = new ArrayList<>(parsed.warnings());
        uploadWarnings.addAll(aliases.warnings());
        lastWarnings.put(dialect, List.copyOf(uploadWarnings));

        SchemaProvisioning provisioner = provisioners.get(dialect);
        provisioner.provision(parsed);

        SeedReport seeded = seeders.get(dialect).seed(parsed);
        List<String> finished = provisioner.finish(parsed);
        Map<String, Integer> rowCounts = seeded.rowCounts();
        if (!seeded.warnings().isEmpty() || !finished.isEmpty()) {
            List<String> combined = new ArrayList<>(uploadWarnings);
            combined.addAll(seeded.warnings());
            combined.addAll(finished);
            lastWarnings.put(dialect, List.copyOf(combined));
        }

        SchemaSnapshot snapshot = new SchemaSnapshot(
                "upload-" + UUID.randomUUID().toString().substring(0, 8),
                label == null || label.isBlank() ? "Uploaded schema" : label,
                provisioner.uploadDatabase(),
                parsed.toKnownTables(),
                parsed.allForeignKeys(),
                rowCounts,
                Instant.now(),
                dialect,
                parsed.indexes(),
                parsed.fieldTypes(),
                parsed.enums(),
                aliases.aliases(),
                parsed.primaryKeys());

        log.info("Uploaded {} schema '{}': {} tables, {} rows total",
                dialect.label(), snapshot.label(), snapshot.tables().size(),
                rowCounts.values().stream().mapToInt(Integer::intValue).sum());

        return activeSchema.publish(snapshot);
    }

    public SchemaSnapshot reset(Dialect dialect) {
        lastWarnings.put(dialect, List.of());
        return activeSchema.resetToBuiltIn(dialect);
    }

    public List<String> lastWarnings(Dialect dialect) {
        return lastWarnings.getOrDefault(dialect, List.of());
    }

    private void requireMongoUri() {
        String uri = environment.getProperty("spring.data.mongodb.uri");
        if (uri == null || uri.isBlank()) {
            throw new SchemaUploadException(
                    "MongoDB upload needs spring.data.mongodb.uri (set it with the MONGO_URI environment "
                            + "variable) pointing at a running mongod, and it is not set.");
        }
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
