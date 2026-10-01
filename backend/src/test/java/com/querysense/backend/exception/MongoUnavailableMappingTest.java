package com.querysense.backend.exception;

import com.querysense.backend.controller.SchemaController;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.upload.SchemaUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MongoUnavailableMappingTest {

    @Test
    @DisplayName("an unreachable MongoDB during upload is a 503 naming MongoDB")
    void unreachableMongoIs503() throws Exception {
        SchemaUploadService uploadService = mock(SchemaUploadService.class);
        when(uploadService.upload(anyString(), anyString(), eq(Dialect.MONGODB)))
                .thenThrow(new MongoUnavailableException("MongoDB is not reachable", null));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SchemaController(uploadService, new ActiveSchemaProvider(), 2_097_152))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MockMultipartFile file = new MockMultipartFile("file", "shop.json", "application/json",
                "{\"collections\": []}".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/schema/upload").file(file).param("dialect", "MONGODB"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("MongoDB")));
    }
}
