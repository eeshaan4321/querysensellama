package com.querysense.backend.schema.upload;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Provisions and seeds the built-in MongoDB demo the first time its database has none of its collections. */
@Component
public class MongoDemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(MongoDemoSeeder.class);
    private static final String DEMO_FILE = "mongo/demo-collections.json";

    private final MongoClient client;
    private final JsonSchemaUploadParser parser;
    private final SchemaUploadValidator validator;
    private final MongoProvisioner provisioner;
    private final MongoSeeder seeder;
    private final ActiveSchemaProvider activeSchema;
    private final boolean enabled;

    public MongoDemoSeeder(MongoClient client,
                           JsonSchemaUploadParser parser,
                           SchemaUploadValidator validator,
                           MongoProvisioner provisioner,
                           MongoSeeder seeder,
                           ActiveSchemaProvider activeSchema,
                           @Value("${querysense.mongo.demo.seed-on-startup:true}") boolean enabled) {
        this.client = client;
        this.parser = parser;
        this.validator = validator;
        this.provisioner = provisioner;
        this.seeder = seeder;
        this.activeSchema = activeSchema;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!enabled) {
            return;
        }
        Thread seeding = new Thread(this::seedIfEmpty, "mongo-demo-seed");
        seeding.setDaemon(true);
        seeding.start();
    }

    boolean seedIfEmpty() {
        String database = KnownMongoSchema.CATALOG;
        try {
            Set<String> existing = client.getDatabase(database).listCollectionNames().into(new HashSet<>());
            boolean present = KnownMongoSchema.TABLES.stream().map(KnownSchema.Table::name).anyMatch(existing::contains);
            if (present) {
                log.info("MongoDB database '{}' already has demo collections; left as it is. Drop the database "
                        + "to have the demo provisioned and seeded again.", database);
                return false;
            }
            ParsedSchema demo = parser.parse(readDemo());
            validator.validate(demo, Dialect.MONGODB);
            provisioner.provision(demo, database);
            SeedReport report = seeder.seed(demo, database);
            log.info("Seeded the MongoDB demo into '{}': {}", database, report.rowCounts());
            report.warnings().forEach(w -> log.warn("MongoDB demo: {}", w));
            if (activeSchema.isBuiltIn(Dialect.MONGODB)) {
                activeSchema.resetToBuiltIn(Dialect.MONGODB);
            }
            return true;
        } catch (MongoUnavailableException | MongoTimeoutException | MongoSocketException e) {
            log.warn("MongoDB is not reachable, so the demo was not seeded: {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Could not seed the MongoDB demo into '{}': {}", database, e.getMessage());
            return false;
        }
    }

    private static String readDemo() {
        try {
            return new ClassPathResource(DEMO_FILE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DEMO_FILE, e);
        }
    }
}
