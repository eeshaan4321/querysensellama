package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.schema.ddl.ParsedSchema;

/** Reads an uploaded schema file into a parsed schema, for one dialect. */
public interface SchemaUploadParser extends DialectAware {
    ParsedSchema parse(String text);
}
