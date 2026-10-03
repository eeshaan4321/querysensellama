package com.querysense.backend.controller;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.SchemaUploadRequest;
import com.querysense.backend.dto.SchemaUploadResponse;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownMongoSchema;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(uploadService.lastWarnings(any())).thenReturn(List.of());
        activeSchema = new ActiveSchemaProvider();
        controller = new SchemaController(uploadService, activeSchema, 2_097_152);
    }

    @Test
    @DisplayName("a multipart upload passes the file's text and its filename as the label")
    void uploadsMultipart() {
        when(uploadService.upload(anyString(), eq("shop.sql"), eq(Dialect.MYSQL))).thenReturn(UPLOADED);
        MockMultipartFile file = new MockMultipartFile(
                "file", "shop.sql", "text/plain",
                "CREATE TABLE customers (id INT);".getBytes(StandardCharsets.UTF_8));

        SchemaUploadResponse response = controller.uploadFile(file, null);

        assertThat(response.schemaId()).isEqualTo("upload-1");
        assertThat(response.builtIn()).isFalse();
        assertThat(response.tables()).singleElement()
                .satisfies(t -> assertThat(t.rowCount()).isEqualTo(500));
        verify(uploadService).upload("CREATE TABLE customers (id INT);", "shop.sql", Dialect.MYSQL);
    }

    @Test
    @DisplayName("a file larger than the cap is refused before it is read as a schema")
    void refusesOversizeFile() {
        byte[] big = new byte[2_097_153];
        MockMultipartFile file = new MockMultipartFile("file", "big.sql", "text/plain", big);

        assertThatThrownBy(() -> controller.uploadFile(file, null))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("an empty file is refused with a message naming what to upload")
    void refusesEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.sql", "text/plain", new byte[0]);

        assertThatThrownBy(() -> controller.uploadFile(file, null))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a JSON upload passes the pasted DDL through")
    void uploadsJson() {
        when(uploadService.upload(anyString(), anyString(), any())).thenReturn(UPLOADED);
        SchemaUploadRequest request = new SchemaUploadRequest();
        request.setDdl("CREATE TABLE customers (id INT);");
        request.setLabel("pasted");

        controller.uploadJson(request);

        verify(uploadService).upload("CREATE TABLE customers (id INT);", "pasted", Dialect.MYSQL);
    }

    @Test
    @DisplayName("GET /active describes the built-in schema when nothing is uploaded")
    void readsActiveSchema() {
        SchemaUploadResponse response = controller.active(null);

        assertThat(response.builtIn()).isTrue();
        assertThat(response.catalog()).isNull();
        assertThat(response.tables()).hasSize(KnownSchema.TABLES.size());
        assertThat(response.foreignKeys()).hasSize(KnownSchema.FOREIGN_KEYS.size());
    }

    @Test
    @DisplayName("GET /active follows an upload once one is published")
    void activeFollowsUpload() {
        activeSchema.publish(UPLOADED);

        SchemaUploadResponse response = controller.active(null);

        assertThat(response.builtIn()).isFalse();
        assertThat(response.catalog()).isEqualTo("querysense_uploads");
    }

    @Test
    @DisplayName("POST /reset returns the built-in schema")
    void resets() {
        when(uploadService.reset(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());

        assertThat(controller.reset(null).builtIn()).isTrue();
    }

    private static final SchemaSnapshot MONGO_UPLOADED = new SchemaSnapshot(
            "upload-2", "shop.json", "querysense_uploads",
            List.of(new KnownSchema.Table("orders", List.of("_id", "items.quantity"), List.of("items"))),
            List.of(), Map.of("orders", 40), Instant.now(), Dialect.MONGODB,
            List.of(new IndexSpec("orders", List.of(new IndexSpec.KeyPart("items.quantity", 1)), false)),
            Map.of("orders", Map.of("_id", "objectId", "items.quantity", "int")));

    @Test
    @DisplayName("a .json file with dialect MongoDB is uploaded as MongoDB, and the response says so")
    void uploadsMongoFile() {
        when(uploadService.upload(anyString(), eq("shop.json"), eq(Dialect.MONGODB))).thenReturn(MONGO_UPLOADED);
        MockMultipartFile file = new MockMultipartFile("file", "shop.json", "application/json",
                "{\"collections\": []}".getBytes(StandardCharsets.UTF_8));

        SchemaUploadResponse response = controller.uploadFile(file, "MONGODB");

        assertThat(response.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(response.indexes()).hasSize(1);
        assertThat(response.tables()).singleElement().satisfies(t -> {
            assertThat(t.embeddedArrays()).containsExactly("items");
            assertThat(t.fieldTypes()).containsEntry("items.quantity", "int");
            assertThat(t.rowCount()).isEqualTo(40);
        });
        verify(uploadService).lastWarnings(Dialect.MONGODB);
    }

    @Test
    @DisplayName("a .json file without dialect MongoDB is refused, saying to choose MongoDB")
    void jsonNeedsMongo() {
        MockMultipartFile file = new MockMultipartFile("file", "shop.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.uploadFile(file, null))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("MongoDB");
        verify(uploadService, never()).upload(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a .sql file with dialect MongoDB is refused, saying to choose MySQL")
    void sqlNeedsMySql() {
        MockMultipartFile file = new MockMultipartFile("file", "shop.sql", "text/plain",
                "CREATE TABLE t (id INT);".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.uploadFile(file, "MongoDB"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("MySQL");
    }

    @Test
    @DisplayName("an unknown dialect is a 400")
    void unknownDialect() {
        assertThatThrownBy(() -> controller.active("oracle")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("active with dialect MongoDB describes the built-in Mongo demo")
    void activeMongo() {
        SchemaUploadResponse response = controller.active("MONGODB");

        assertThat(response.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(response.builtIn()).isTrue();
        assertThat(response.catalog()).isEqualTo("querysense");
        assertThat(response.tables()).filteredOn(t -> t.name().equals("orders")).singleElement()
                .satisfies(t -> assertThat(t.embeddedArrays()).containsExactly("items"));
        assertThat(response.indexes()).isNotEmpty();
    }

    @Test
    @DisplayName("reset with dialect MongoDB resets MongoDB")
    void resetMongo() {
        when(uploadService.reset(Dialect.MONGODB)).thenReturn(KnownMongoSchema.builtIn());

        assertThat(controller.reset("MongoDB").dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("the JSON body's dialect is honoured, and 'schema' is accepted for 'ddl'")
    void jsonBodyDialectAndAlias() throws Exception {
        SchemaUploadRequest request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"schema\": \"{}\", \"dialect\": \"MONGODB\", \"label\": \"shop.json\"}",
                SchemaUploadRequest.class);
        when(uploadService.upload("{}", "shop.json", Dialect.MONGODB)).thenReturn(MONGO_UPLOADED);

        assertThat(controller.uploadJson(request).dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("the active schema lists each table's declared aliases")
    void activeSchemaListsAliases() {
        SchemaUploadResponse response = controller.active(null);

        assertThat(response.tables()).filteredOn(t -> t.name().equals("customers")).singleElement()
                .satisfies(t -> assertThat(t.aliases()).containsExactly("buyers"));
    }
}
