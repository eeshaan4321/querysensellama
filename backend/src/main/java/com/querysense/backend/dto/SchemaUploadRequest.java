package com.querysense.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** JSON request body for uploading DDL as text. */
public class SchemaUploadRequest {

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
}
