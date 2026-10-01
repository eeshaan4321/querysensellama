package com.querysense.backend.controller;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.SchemaUploadRequest;
import com.querysense.backend.dto.SchemaUploadResponse;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.upload.SchemaUploadService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** REST endpoints for uploading, viewing and resetting the active schema. */
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaUploadService uploadService;
    private final ActiveSchemaProvider activeSchema;
    private final long maxFileBytes;

    public SchemaController(SchemaUploadService uploadService,
                            ActiveSchemaProvider activeSchema,
                            @Value("${querysense.schema.upload.max-file-bytes:2097152}") long maxFileBytes) {
        this.uploadService = uploadService;
        this.activeSchema = activeSchema;
        this.maxFileBytes = maxFileBytes;
    }

    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    public SchemaUploadResponse uploadFile(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "dialect", required = false) String dialect) {
        Dialect target = dialectOf(dialect);
        if (file == null || file.isEmpty()) {
            throw new SchemaUploadException(target == Dialect.MONGODB
                    ? "The uploaded file was empty. Choose a .json file describing the collections."
                    : "The uploaded file was empty. Choose a .sql file containing CREATE TABLE statements.");
        }
        requireMatchingExtension(file.getOriginalFilename(), target);
        if (file.getSize() > maxFileBytes) {
            throw new SchemaUploadException(
                    "That file is too large (" + (file.getSize() / 1024) + " KB). The limit is "
                            + (maxFileBytes / 1024) + " KB — "
                            + (target == Dialect.MONGODB
                                    ? "upload the collection definitions only, not documents."
                                    : "upload a schema-only dump (`mysqldump --no-data`) rather than one containing data."));
        }

        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SchemaUploadException("The uploaded file could not be read.", e);
        }

        return SchemaUploadResponse.from(
                uploadService.upload(text, file.getOriginalFilename(), target), uploadService.lastWarnings(target));
    }

    @PostMapping(path = "/upload", consumes = "application/json")
    public SchemaUploadResponse uploadJson(@Valid @RequestBody SchemaUploadRequest request) {
        Dialect target = dialectOf(request.getDialect());
        return SchemaUploadResponse.from(
                uploadService.upload(request.getDdl(), request.getLabel(), target), uploadService.lastWarnings(target));
    }

    @GetMapping("/active")
    public SchemaUploadResponse active(@RequestParam(value = "dialect", required = false) String dialect) {
        Dialect target = dialectOf(dialect);
        return SchemaUploadResponse.from(activeSchema.active(target), uploadService.lastWarnings(target));
    }

    @PostMapping("/reset")
    public SchemaUploadResponse reset(@RequestParam(value = "dialect", required = false) String dialect) {
        return SchemaUploadResponse.from(uploadService.reset(dialectOf(dialect)), List.of());
    }

    private static Dialect dialectOf(String dialect) {
        return dialect == null || dialect.isBlank() ? Dialect.MYSQL : Dialect.from(dialect);
    }

    private static void requireMatchingExtension(String filename, Dialect target) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (target == Dialect.MYSQL && name.endsWith(".json")) {
            throw new SchemaUploadException(
                    "That is a .json file, which QuerySense reads as a MongoDB schema. Choose MongoDB as the "
                            + "database and upload it again.");
        }
        if (target == Dialect.MONGODB && name.endsWith(".sql")) {
            throw new SchemaUploadException(
                    "That is a .sql file, which QuerySense reads as a MySQL schema. Choose MySQL as the database, "
                            + "or upload a .json file of MongoDB collections.");
        }
    }
}
