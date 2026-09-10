package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SchemaSeederTest
 *
 * The ordering tests exist because foreign keys are real constraints in the
 * provisioned schema: a child row inserted before its parent is rejected by
 * MySQL, and the table ends up empty — which puts EXPLAIN right back to
 * reporting rows=1 for it, the exact condition seeding exists to prevent.
 */
class SchemaSeederTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaSeeder seeder;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        seeder = new SchemaSeeder(jdbcTemplate, "querysense_uploads", 50, 7L);
    }

    @Test
    @DisplayName("parents are seeded before the children that reference them")
    void ordersTablesTopologically() {
        assertThat(seeder.orderTables(customersAndOrders()))
                .extracting(ParsedTable::name).containsExactly("customers", "orders");
    }

    @Test
    @DisplayName("declaration order in the file does not change the seeding order")
    void orderIsIndependentOfDeclarationOrder() {
        List<ParsedTable> tables = customersAndOrders().tables();
        ParsedSchema reversed = new ParsedSchema(List.of(tables.get(1), tables.get(0)), List.of());

        assertThat(seeder.orderTables(reversed))
                .extracting(ParsedTable::name).containsExactly("customers", "orders");
    }

    @Test
    @DisplayName("a self-referencing table is seeded once, not treated as a cycle to reject")
    void handlesSelfReference() {
        ParsedTable employees = new ParsedTable("employees",
                List.of(pk("id"), col("manager_id", "INT")),
                List.of(new KnownSchema.ForeignKey("employees", "manager_id", "employees", "id")));

        assertThat(seeder.orderTables(new ParsedSchema(List.of(employees), List.of())))
                .extracting(ParsedTable::name).containsExactly("employees");
    }

    @Test
    @DisplayName("a mutual dependency still yields every table rather than dropping one")
    void toleratesCycle() {
        ParsedTable a = new ParsedTable("a", List.of(pk("id"), col("b_id", "INT")),
                List.of(new KnownSchema.ForeignKey("a", "b_id", "b", "id")));
        ParsedTable b = new ParsedTable("b", List.of(pk("id"), col("a_id", "INT")),
                List.of(new KnownSchema.ForeignKey("b", "a_id", "a", "id")));

        assertThat(seeder.orderTables(new ParsedSchema(List.of(a, b), List.of())))
                .extracting(ParsedTable::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("reports how many rows it inserted per table")
    void reportsRowCounts() {
        Map<String, Integer> counts = seeder.seed(customersAndOrders());

        assertThat(counts).containsEntry("customers", 50).containsEntry("orders", 50);
    }

    @Test
    @DisplayName("inserts through a batch, naming the columns explicitly")
    void insertsInBatches() {
        seeder.seed(customersAndOrders());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(sql.capture(), any(List.class));
        assertThat(sql.getAllValues())
                .anyMatch(s -> s.startsWith("INSERT INTO `querysense_uploads`.`customers` (`id`, `name`)")
                        && s.contains("VALUES (?, ?)"));
    }

    @Test
    @DisplayName("runs ANALYZE TABLE on every table so InnoDB statistics are fresh")
    void analysesEveryTable() {
        seeder.seed(customersAndOrders());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
        assertThat(sql.getAllValues())
                .contains("ANALYZE TABLE `querysense_uploads`.`customers`",
                        "ANALYZE TABLE `querysense_uploads`.`orders`");
    }

    @Test
    @DisplayName("a foreign key column is populated from the parent's real id range")
    @SuppressWarnings("unchecked")
    void foreignKeyValuesPointAtRealParents() {
        seeder.seed(customersAndOrders());

        ArgumentCaptor<List<Object[]>> batches = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), batches.capture());

        // orders is (id INT pk, customer_id INT fk). Its rows are the only
        // two-column ones whose second value is an Integer, and every one of
        // those must name a customer id that customers was actually seeded
        // with — otherwise MySQL would have rejected the insert outright.
        List<Object[]> foreignKeyRows = batches.getAllValues().stream()
                .flatMap(List::stream)
                .filter(row -> row.length == 2 && row[1] instanceof Integer)
                .toList();

        assertThat(foreignKeyRows).isNotEmpty();
        assertThat(foreignKeyRows).allSatisfy(row ->
                assertThat((Integer) row[1]).isBetween(1, 50));
    }

    private static ParsedSchema customersAndOrders() {
        ParsedTable customers = new ParsedTable("customers",
                List.of(pk("id"), col("name", "VARCHAR")), List.of());
        ParsedTable orders = new ParsedTable("orders",
                List.of(pk("id"), col("customer_id", "INT")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")));
        return new ParsedSchema(List.of(customers, orders), List.of());
    }

    private static ParsedColumn pk(String name) {
        return new ParsedColumn(name, "INT", List.of(), true, true, false, true);
    }

    private static ParsedColumn col(String name, String type) {
        return new ParsedColumn(name, type, List.of("64"), true, false, false, false);
    }
}
