package com.querysense.backend.controller;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.SchemaUploadRequest;
import com.querysense.backend.dto.SchemaUploadResponse;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.upload.SchemaUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaControllerTest {

    private SchemaUploadService uploadService;
    private ActiveSchemaProvider activeSchema;
    private SchemaController controller;

    private static final SchemaSnapshot UPLOADED = new SchemaSnapshot(
            "upload-1", "shop.sql", "querysense_uploads",
            List.of(new KnownSchema.Table("customers", List.of("id", "name"))),
            List.of(), Map.of("customers", 500), Instant.now());

    @BeforeEach
    void setUp() {
        uploadService = mock(SchemaUploadService.class);
        when(uploadService.lastWarnings(Dialect.MYSQL)).thenReturn(List.of());
        activeSchema = new ActiveSchemaProvider();
        controller = new SchemaController(uploadService, activeSchema, 2_097_152);
    }

    @Test
    @DisplayName("a multipart upload passes the file's text and its filename as the label")
    void uploadsMultipart() {
        when(uploadService.upload(anyString(), eq("shop.sql"))).thenReturn(UPLOADED);
        MockMultipartFile file = new MockMultipartFile(
                "file", "shop.sql", "text/plain",
                "CREATE TABLE customers (id INT);".getBytes(StandardCharsets.UTF_8));

        SchemaUploadResponse response = controller.uploadFile(file);

        assertThat(response.schemaId()).isEqualTo("upload-1");
        assertThat(response.builtIn()).isFalse();
        assertThat(response.tables()).singleElement()
                .satisfies(t -> assertThat(t.rowCount()).isEqualTo(500));
        verify(uploadService).upload("CREATE TABLE customers (id INT);", "shop.sql");
    }

    @Test
    @DisplayName("a file larger than the cap is refused before it is read as a schema")
    void refusesOversizeFile() {
        byte[] big = new byte[2_097_153];
        MockMultipartFile file = new MockMultipartFile("file", "big.sql", "text/plain", big);

        assertThatThrownBy(() -> controller.uploadFile(file))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("an empty file is refused with a message naming what to upload")
    void refusesEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.sql", "text/plain", new byte[0]);

        assertThatThrownBy(() -> controller.uploadFile(file))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a JSON upload passes the pasted DDL through")
    void uploadsJson() {
        when(uploadService.upload(anyString(), anyString())).thenReturn(UPLOADED);
        SchemaUploadRequest request = new SchemaUploadRequest();
        request.setDdl("CREATE TABLE customers (id INT);");
        request.setLabel("pasted");

        controller.uploadJson(request);

        verify(uploadService).upload("CREATE TABLE customers (id INT);", "pasted");
    }

    @Test
    @DisplayName("GET /active describes the built-in schema when nothing is uploaded")
    void readsActiveSchema() {
        SchemaUploadResponse response = controller.active();

        assertThat(response.builtIn()).isTrue();
        assertThat(response.catalog()).isNull();
        assertThat(response.tables()).hasSize(KnownSchema.TABLES.size());
        assertThat(response.foreignKeys()).hasSize(KnownSchema.FOREIGN_KEYS.size());
    }

    @Test
    @DisplayName("GET /active follows an upload once one is published")
    void activeFollowsUpload() {
        activeSchema.publish(UPLOADED);

        SchemaUploadResponse response = controller.active();

        assertThat(response.builtIn()).isFalse();
        assertThat(response.catalog()).isEqualTo("querysense_uploads");
    }

    @Test
    @DisplayName("POST /reset returns the built-in schema")
    void resets() {
        when(uploadService.reset(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        assertThat(controller.reset().builtIn()).isTrue();
    }
}
