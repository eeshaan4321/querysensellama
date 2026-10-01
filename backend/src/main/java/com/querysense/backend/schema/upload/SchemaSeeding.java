package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.schema.ddl.ParsedSchema;

/** Fills an uploaded schema with generated data, for one dialect. */
public interface SchemaSeeding extends DialectAware {
    SeedReport seed(ParsedSchema schema);
}
