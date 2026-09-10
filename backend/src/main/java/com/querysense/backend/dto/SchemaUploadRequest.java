package com.querysense.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The JSON body form of an upload — for DDL pasted into the UI rather than
 * chosen as a file. The multipart form is handled separately on the
 * controller; both funnel into the same service call.
 */
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
