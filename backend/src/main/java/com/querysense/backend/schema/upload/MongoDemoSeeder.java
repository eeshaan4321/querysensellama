package com.querysense.backend.schema.upload;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.Document;
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
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Provisions and seeds the built-in MongoDB demo until a seeding has completed, which it then marks. */
@Component
public class MongoDemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(MongoDemoSeeder.class);
    private static final String DEMO_FILE = "mongo/demo-collections.json";
    static final String SEED_MARKER = "querysense_demo_seed";

    private final MongoClient client;
    private final JsonSchemaUploadParser parser;
    private final SchemaUploadValidator validator;
    private final MongoProvisioner provisioner;
    private final MongoSeeder seeder;
    private final ActiveSchemaProvider activeSchema;
    private final boolean enabled;
    private final int seedRows;

    public MongoDemoSeeder(MongoClient client,
                           JsonSchemaUploadParser parser,
                           SchemaUploadValidator validator,
                           MongoProvisioner provisioner,
                           MongoSeeder seeder,
                           ActiveSchemaProvider activeSchema,
                           @Value("${querysense.mongo.demo.seed-on-startup:true}") boolean enabled,
                           @Value("${querysense.schema.upload.seed-rows:100}") int seedRows) {
        this.client = client;
        this.parser = parser;
        this.validator = validator;
        this.provisioner = provisioner;
        this.seeder = seeder;
        this.activeSchema = activeSchema;
        this.enabled = enabled;
        this.seedRows = seedRows;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!enabled) {
            return;
        }
        Thread seeding = new Thread(this::seedIfIncomplete, "mongo-demo-seed");
        seeding.setDaemon(true);
        seeding.start();
    }

    boolean seedIfIncomplete() {
        String database = KnownMongoSchema.CATALOG;
        try {
            MongoDatabase db = client.getDatabase(database);
            if (db.getCollection(SEED_MARKER).countDocuments(Filters.eq("_id", "demo")) > 0) {
                log.info("The MongoDB demo in '{}' is already seeded; left as it is.", database);
                return false;
            }
            Set<String> existing = db.listCollectionNames().into(new HashSet<>());
            List<String> demo = KnownMongoSchema.TABLES.stream().map(KnownSchema.Table::name).toList();
            List<String> present = demo.stream().filter(existing::contains).toList();
            if (present.size() == demo.size()
                    && present.stream().allMatch(name -> db.getCollection(name).estimatedDocumentCount() >= seedRows)) {
                markSeeded(db);
                log.info("The MongoDB demo in '{}' is complete; marked as seeded and left as it is.", database);
                return false;
            }
            if (!present.isEmpty()) {
                log.warn("The MongoDB demo in '{}' is incomplete, so a previous seeding stopped partway. Its {} "
                        + "collections are provisioned and seeded again.", database, demo.size());
            }
            ParsedSchema schema = parser.parse(readDemo());
            validator.validate(schema, Dialect.MONGODB);
            provisioner.provision(schema, database);
            SeedReport report = seeder.seed(schema, database);
            markSeeded(db);
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

    private static void markSeeded(MongoDatabase db) {
        db.getCollection(SEED_MARKER).replaceOne(Filters.eq("_id", "demo"),
                new Document("_id", "demo").append("seededAt", new Date()), new ReplaceOptions().upsert(true));
    }

    private static String readDemo() {
        try {
            return new ClassPathResource(DEMO_FILE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DEMO_FILE, e);
        }
    }
}
