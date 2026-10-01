package com.querysense.backend.schema.upload;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creates an uploaded MongoDB schema's collections, validators and indexes. */
@Component
public class MongoProvisioner implements SchemaProvisioning {

    private static final Logger log = LoggerFactory.getLogger(MongoProvisioner.class);
    private static final int UNAUTHORIZED = 13;

    private final MongoClient client;
    private final String uploadDatabase;

    public MongoProvisioner(MongoClient client,
                            @Value("${querysense.schema.upload.mongo-database:querysense_uploads}") String uploadDatabase) {
        this.client = client;
        this.uploadDatabase = uploadDatabase;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public String uploadDatabase() {
        return uploadDatabase;
    }

    @Override
    public void provision(ParsedSchema schema) {
        provision(schema, uploadDatabase);
    }

    public void provision(ParsedSchema schema, String database) {
        MongoDatabase db = client.getDatabase(database);
        String current = null;
        try {
            for (ParsedTable collection : schema.tables()) {
                current = collection.name();
                db.getCollection(collection.name()).drop();
                db.createCollection(collection.name(), options(collection));
                for (IndexSpec index : schema.indexesOf(collection.name())) {
                    db.getCollection(collection.name()).createIndex(keys(index), new IndexOptions().unique(index.unique()));
                }
            }
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw new MongoUnavailableException(
                    "MongoDB is not reachable, so the schema could not be provisioned into '" + database
                            + "'. Check that mongod is running at the address in spring.data.mongodb.uri.", e);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == UNAUTHORIZED) {
                throw new SchemaUploadException(
                        "MongoDB refused to create collections in '" + database + "'. The account QuerySense "
                                + "connects with needs the readWrite and dbAdmin roles on that database.", e);
            }
            throw new SchemaUploadException(
                    "MongoDB could not create the collection '" + current + "': " + e.getErrorMessage(), e);
        }
        log.info("Provisioned {} collection(s) into '{}'", schema.tables().size(), database);
    }

    private static CreateCollectionOptions options(ParsedTable collection) {
        CreateCollectionOptions options = new CreateCollectionOptions();
        if (collection.validator() != null) {
            options.validationOptions(new ValidationOptions()
                    .validator(collection.validator())
                    .validationLevel(ValidationLevel.STRICT)
                    .validationAction(ValidationAction.ERROR));
        }
        return options;
    }

    private static Document keys(IndexSpec index) {
        Document keys = new Document();
        index.keys().forEach(part -> keys.append(part.column(), part.direction()));
        return keys;
    }
}
