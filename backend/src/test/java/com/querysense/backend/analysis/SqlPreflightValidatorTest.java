package com.querysense.backend.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlPreflightValidatorTest {

    private SqlPreflightValidator validator;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getColumnNames("orders")).thenReturn(List.of("id", "customer_id", "order_date", "status"));
        when(metadata.getColumnNames("customers")).thenReturn(List.of("id", "name", "city"));
        validator = new SqlPreflightValidator(metadata);
    }

    @Test
    @DisplayName("an unknown column on the queried table is still caught")
    void unknownColumnCaught() {
        assertThat(validator.validate("SELECT age FROM customers", "customers"))
                .hasValueSatisfying(w -> assertThat(w).contains("'age'"));
        assertThat(validator.validate("SELECT * FROM orders o WHERE o.amount > 5", "orders"))
                .hasValueSatisfying(w -> assertThat(w).contains("'amount'"));
    }

    @Test
    @DisplayName("joinedColumnIsNotTheBaseTable: a filter on a joined table is not checked against the base table")
    void joinedColumnIsNotTheBaseTable() {
        assertThat(validator.validate("SELECT DISTINCT orders.* FROM orders JOIN customers "
                + "ON orders.customer_id = customers.id WHERE customers.city = 'mumbai'", "orders")).isEmpty();
        assertThat(validator.validate("SELECT o.id, c.name FROM orders o JOIN customers c "
                + "ON o.customer_id = c.id WHERE c.city = 'pune'", "orders")).isEmpty();
    }
}
