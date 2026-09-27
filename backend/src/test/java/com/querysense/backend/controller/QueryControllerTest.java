package com.querysense.backend.controller;

import com.querysense.backend.exception.GlobalExceptionHandler;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.service.QueryGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A malformed generate request body is a 400, never an unhandled 500. */
class QueryControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        QueryGenerationService service = mock(QueryGenerationService.class);
        QueryHistoryRepository queryHistoryRepository = mock(QueryHistoryRepository.class);
        QueryController controller = new QueryController(service, queryHistoryRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("an unknown resourceLevel is a 400 naming the field and the allowed values")
    void unknownResourceLevelIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/query/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"show all customers\",\"databaseType\":\"MySQL\",\"resourceLevel\":\"EXTREME\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("resourceLevel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LOW")));
    }

    @Test
    @DisplayName("malformed JSON is a 400, not an unhandled 500")
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/query/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":"))
                .andExpect(status().isBadRequest());
    }
}
