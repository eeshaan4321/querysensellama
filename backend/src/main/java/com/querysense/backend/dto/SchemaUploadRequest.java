package com.querysense.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

/** JSON request body for uploading a schema as text: DDL for MySQL, a collections document for MongoDB. */
public class SchemaUploadRequest {

    @JsonAlias("schema")
    @NotBlank(message = "ddl must not be blank")
    private String ddl;

    private String label;

    public String getDdl() {
        return ddl;
    }

    public void setDdl(String ddl) {
        this.ddl = ddl;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    private String dialect;

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }
}
