package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
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
                    "Foreign key " + fk.fromTable() + "." + fk.fromColumn() + " references the table '"
                            + fk.toTable() + "', which is not in the uploaded schema."));
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
