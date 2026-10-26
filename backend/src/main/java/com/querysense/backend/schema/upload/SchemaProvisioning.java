package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.schema.ddl.ParsedSchema;

import java.util.List;

/** Creates an uploaded schema's tables or collections in the upload database, for one dialect. */
public interface SchemaProvisioning extends DialectAware {
    String uploadDatabase();

    void provision(ParsedSchema schema);

    default List<String> finish(ParsedSchema schema) {
        return List.of();
    }
}
