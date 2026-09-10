package com.querysense.backend.controller;

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

/**
 * SchemaController
 *
 * Three endpoints, deliberately separate from QueryController: uploading a
 * schema is not a query operation and the two share no request shape.
 *
 * CORS is not annotated here. AppConfig's global WebMvcConfigurer already
 * maps /** — a per-controller @CrossOrigin was the cause of a real bug
 * where /analyze and /schema-advisor worked in curl and were blocked in a
 * browser, so this deliberately does not reintroduce the pattern.
 *
 * WHY BOTH A MULTIPART AND A JSON FORM:
 * A file picker is what someone with a mysqldump wants; a textarea is what
 * someone demoing wants, and pasting four CREATE TABLE statements should
 * not require saving a file first. Both funnel into the same service call.
 *
 * WHY THE SIZE CAP IS CHECKED HERE AND NOT IN THE PARSER:
 * Rejecting a 200MB file should not require reading it into a String and
 * splitting it into statements first.
 */
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
    public SchemaUploadResponse uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SchemaUploadException(
                    "The uploaded file was empty. Choose a .sql file containing CREATE TABLE statements.");
        }
        if (file.getSize() > maxFileBytes) {
            throw new SchemaUploadException(
                    "That file is too large (" + (file.getSize() / 1024) + " KB). The limit is "
                            + (maxFileBytes / 1024) + " KB — upload a schema-only dump "
                            + "(`mysqldump --no-data`) rather than one containing data.");
        }

        String ddl;
        try {
            ddl = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SchemaUploadException("The uploaded file could not be read.", e);
        }

        return SchemaUploadResponse.from(
                uploadService.upload(ddl, file.getOriginalFilename()), uploadService.lastWarnings());
    }

    @PostMapping(path = "/upload", consumes = "application/json")
    public SchemaUploadResponse uploadJson(@Valid @RequestBody SchemaUploadRequest request) {
        return SchemaUploadResponse.from(
                uploadService.upload(request.getDdl(), request.getLabel()), uploadService.lastWarnings());
    }

    @GetMapping("/active")
    public SchemaUploadResponse active() {
        return SchemaUploadResponse.from(activeSchema.active(), uploadService.lastWarnings());
    }

    @PostMapping("/reset")
    public SchemaUploadResponse reset() {
        return SchemaUploadResponse.from(uploadService.reset(), List.of());
    }
}
