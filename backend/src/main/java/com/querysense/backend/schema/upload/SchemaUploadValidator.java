package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.DdlSchemaParser;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates an uploaded schema before anything touches the database. */
@Component
public class SchemaUploadValidator {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private final int maxTables;

    public SchemaUploadValidator(@Value("${querysense.schema.upload.max-tables:40}") int maxTables) {
        this.maxTables = maxTables;
    }

    public void validate(ParsedSchema schema) {
        validate(schema, Dialect.MYSQL);
    }

    public void validate(ParsedSchema schema, Dialect dialect) {
        if (dialect == Dialect.MONGODB) {
            validateMongo(schema);
        } else {
            validateSql(schema);
        }
    }

    private void validateMongo(ParsedSchema schema) {
        if (schema.tables().isEmpty()) {
            throw new SchemaUploadException(
                    "No collections were found in the uploaded file. Upload a JSON document with a "
                            + "\"collections\" array, each collection with a $jsonSchema validator.");
        }
        if (schema.tables().size() > maxTables) {
            throw new SchemaUploadException(
                    "The uploaded schema has " + schema.tables().size()
                            + " collections, which is more than the " + maxTables + " this build accepts.");
        }

        Set<String> seen = new HashSet<>();
        for (ParsedTable collection : schema.tables()) {
            requireSafe(collection.name(), "Collection name");
            if (!seen.add(collection.name().toLowerCase(Locale.ROOT))) {
                throw new SchemaUploadException(
                        "The uploaded schema declares the collection '" + collection.name() + "' more than once.");
            }
            for (ParsedColumn field : collection.columns()) {
                for (String segment : field.name().split("\\.")) {
                    requireSafe(segment, "Field name");
                }
                if (field.notNull() && !JsonSchemaUploadParser.GENERATED_TYPES.contains(field.type())) {
                    throw new SchemaUploadException(
                            "Field '" + collection.name() + "." + field.name() + "' is required and has bsonType '"
                                    + field.type() + "', which QuerySense cannot generate values for. Make it "
                                    + "optional, or use one of: string, int, long, double, decimal, bool, date, "
                                    + "objectId, object, array.");
                }
            }
            if (collection.validator() != null
                    && collection.validator().get("$jsonSchema") instanceof Document jsonSchema
                    && Boolean.FALSE.equals(jsonSchema.get("additionalProperties"))
                    && !(jsonSchema.get("properties") instanceof Document properties && properties.containsKey("_id"))) {
                throw new SchemaUploadException(
                        "Collection '" + collection.name() + "' sets additionalProperties: false but does not "
                                + "declare _id in properties, so MongoDB would reject every document. Add "
                                + "\"_id\": {\"bsonType\": \"objectId\"} to its properties.");
            }
        }

        for (KnownSchema.ForeignKey reference : schema.allForeignKeys()) {
            String name = reference.fromTable() + "." + reference.fromColumn();
            ParsedColumn source = schema.table(reference.fromTable())
                    .flatMap(t -> t.column(reference.fromColumn()))
                    .orElseThrow(() -> new SchemaUploadException(
                            "The reference " + name + " names a field that is not declared in '"
                                    + reference.fromTable() + "''s $jsonSchema."));
            ParsedTable target = schema.table(reference.toTable()).orElseThrow(() -> new SchemaUploadException(
                    "The reference " + name + " points at the collection '" + reference.toTable()
                            + "', which is not in the uploaded schema."));
            String targetName = reference.toTable() + "." + reference.toColumn();
            ParsedColumn targetField = target.column(reference.toColumn()).orElseThrow(() -> new SchemaUploadException(
                    "The reference " + name + " points at '" + targetName + "', which is not declared."));
            for (String array : target.embeddedArrays()) {
                if (reference.toColumn().equals(array) || reference.toColumn().startsWith(array + ".")) {
                    throw new SchemaUploadException(
                            "The reference " + name + " points at '" + targetName + "', which is inside the "
                                    + "embedded array '" + array + "'. A reference must point at a field with "
                                    + "one value per document.");
                }
            }
            if (!"any".equals(source.type()) && !"any".equals(targetField.type())
                    && !source.type().equals(targetField.type())) {
                throw new SchemaUploadException(
                        "The reference " + name + " is bsonType '" + source.type() + "' but '" + targetName
                                + "' is '" + targetField.type() + "', so MongoDB's validator would reject the "
                                + "seeded values. Declare both with the same bsonType.");
            }
        }

        for (IndexSpec index : schema.indexes()) {
            ParsedTable collection = schema.table(index.table()).orElseThrow();
            for (IndexSpec.KeyPart part : index.keys()) {
                String path = part.column();
                boolean declared = collection.column(path).isPresent()
                        || collection.embeddedArrays().contains(path)
                        || collection.columns().stream().anyMatch(c -> c.name().startsWith(path + "."));
                if (!declared) {
                    throw new SchemaUploadException(
                            "The index on '" + index.table() + "' names the field '" + path
                                    + "', which is not declared in its $jsonSchema.");
                }
            }
        }
    }

    private void validateSql(ParsedSchema schema) {
        if (schema.tables().isEmpty()) {
            throw new SchemaUploadException(
                    "No CREATE TABLE statements were found in the uploaded file. "
                            + "Upload a schema dump - for example the output of `mysqldump --no-data`.");
        }
        if (schema.tables().size() > maxTables) {
            throw new SchemaUploadException(
                    "The uploaded schema has " + schema.tables().size()
                            + " tables, which is more than the " + maxTables + " this build accepts.");
        }

        Set<String> seen = new HashSet<>();
        for (ParsedTable table : schema.tables()) {
            requireSafe(table.name(), "Table name");
            if (!seen.add(table.name().toLowerCase(Locale.ROOT))) {
                throw new SchemaUploadException(
                        "The uploaded schema declares the table '" + table.name() + "' more than once.");
            }
            if (table.columns().isEmpty()) {
                throw new SchemaUploadException("Table '" + table.name() + "' has no columns.");
            }
            for (ParsedColumn column : table.columns()) {
                requireSafe(column.name(), "Column name");
            }
        }

        for (KnownSchema.ForeignKey fk : schema.allForeignKeys()) {
            ParsedTable target = schema.table(fk.toTable()).orElseThrow(() -> new SchemaUploadException(
                    DdlSchemaParser.skipReason(schema.warnings(), fk.toTable())
                            .map(reason -> "Foreign key " + fk.fromTable() + "." + fk.fromColumn() + " references the table '"
                                    + fk.toTable() + "', which is in the file but could not be read (" + reason + ").")
                            .orElse("Foreign key " + fk.fromTable() + "." + fk.fromColumn() + " references the table '"
                                    + fk.toTable() + "', which is not in the uploaded schema.")));
            if (target.column(fk.toColumn()).isEmpty()) {
                throw new SchemaUploadException(
                        "Foreign key " + fk.fromTable() + "." + fk.fromColumn() + " references '"
                                + fk.toTable() + "." + fk.toColumn() + "', which does not exist.");
            }
        }
    }

    private void requireSafe(String identifier, String what) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new SchemaUploadException(
                    what + " '" + identifier + "' is not supported. Names must be 1-64 characters "
                            + "of letters, digits and underscores.");
        }
    }
}
