package com.querysense.backend.controller;

import com.querysense.backend.dto.CompareRequest;
import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.service.QueryComparisonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryComparisonControllerTest {

    private QueryComparisonService service;
    private QueryComparisonController controller;

    @BeforeEach
    void setUp() {
        service = mock(QueryComparisonService.class);
        controller = new QueryComparisonController(service);
    }

    @Test
    @DisplayName("the request's query and database type reach the service unchanged")
    void passesRequestThrough() {
        CompareRequest request = new CompareRequest();
        request.setQuery("SELECT * FROM customers;");
        request.setDatabaseType("MySQL");

        CompareResponse expected = new CompareResponse();
        expected.setOriginalQuery("SELECT * FROM customers;");
        when(service.compare(eq("SELECT * FROM customers;"), eq("MySQL"), isNull())).thenReturn(expected);

        CompareResponse actual = controller.compare(request);

        assertThat(actual).isSameAs(expected);
        verify(service).compare("SELECT * FROM customers;", "MySQL", null);
    }

    @Test
    @DisplayName("the database type is echoed verbatim, as the other endpoints do")
    void echoesDatabaseTypeVerbatim() {
        CompareRequest request = new CompareRequest();
        request.setQuery("db.customers.find({})");
        request.setDatabaseType("MongoDB");
        when(service.compare(eq("db.customers.find({})"), eq("MongoDB"), isNull()))
                .thenReturn(new CompareResponse());

        controller.compare(request);

        verify(service).compare("db.customers.find({})", "MongoDB", null);
    }

    @Test
    @DisplayName("the user's question, when sent, reaches the service so the rewrite lands on the right history row")
    void passesUserInputThrough() {
        CompareRequest request = new CompareRequest();
        request.setQuery("db.customers.find({})");
        request.setDatabaseType("MongoDB");
        request.setUserInput("all customers");

        controller.compare(request);

        verify(service).compare("db.customers.find({})", "MongoDB", "all customers");
    }
}
