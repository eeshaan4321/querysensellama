package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
        Map<String, Integer> counts = seeder.seed(customersAndOrders()).rowCounts();

        assertThat(counts).containsEntry("customers", 50).containsEntry("orders", 50);
    }

    @Test
    @DisplayName("a rejected batch is reported as a warning, not swallowed")
    void reportsRejectedBatchesAsWarnings() {
        doThrow(new DataIntegrityViolationException(
                "Data truncation: Out of range value for column 'status' at row 1"))
                .when(jdbcTemplate).batchUpdate(anyString(), any(List.class));

        SeedReport report = seeder.seed(customersAndOrders());

        assertThat(report.rowCounts()).containsEntry("customers", 0);
        assertThat(report.warnings())
                .anyMatch(w -> w.contains("customers") && w.contains("Seeded only 0"));
        assertThat(report.warnings())
                .anyMatch(w -> w.contains("Out of range value for column 'status'"));
    }

    @Test
    @DisplayName("a fully successful seed produces no warnings")
    void quietWhenEverythingSeeds() {
        assertThat(seeder.seed(customersAndOrders()).warnings()).isEmpty();
    }

    @Test
    @DisplayName("a TINYINT primary key caps the table at 127 and warns about it")
    void narrowPrimaryKeyCapsRowsAndWarns() {
        SchemaSeeder production = new SchemaSeeder(jdbcTemplate, "querysense_uploads", 12000, 7L);
        ParsedTable narrow = new ParsedTable(
                "flags",
                List.of(new ParsedColumn("id", "TINYINT", List.of(), true, false, false, true),
                        col("label", "VARCHAR")),
                List.of());

        assertThat(production.seedableRowsFor(narrow)).isEqualTo(127L);

        SeedReport report = production.seed(new ParsedSchema(List.of(narrow), List.of()));

        assertThat(report.rowCounts()).containsEntry("flags", 127);
        assertThat(report.warnings())
                .anyMatch(w -> w.contains("flags") && w.contains("127") && w.contains("12000"));
    }

    @Test
    @DisplayName("seedableRowsFor stops at the narrowest key column of several")
    void seedableRowsStopsAtNarrowestKey() {
        SchemaSeeder production = new SchemaSeeder(jdbcTemplate, "querysense_uploads", 12000, 7L);
        ParsedTable mixed = new ParsedTable(
                "mixed",
                List.of(new ParsedColumn("id", "INT", List.of(), true, false, false, true),
                        new ParsedColumn("slot", "SMALLINT", List.of(), true, false, true, false),
                        new ParsedColumn("tag", "TINYINT", List.of(), true, false, true, false)),
                List.of());

        assertThat(production.seedableRowsFor(mixed)).isEqualTo(127L);
    }

    @Test
    @DisplayName("a wide key does not cap the table at all")
    void wideKeyDoesNotCap() {
        SchemaSeeder production = new SchemaSeeder(jdbcTemplate, "querysense_uploads", 12000, 7L);
        ParsedTable wide = new ParsedTable(
                "wide",
                List.of(new ParsedColumn("id", "INT", List.of(), true, false, false, true)),
                List.of());

        assertThat(production.seedableRowsFor(wide)).isEqualTo(12000L);
        assertThat(production.seed(new ParsedSchema(List.of(wide), List.of())).warnings()).isEmpty();
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

        List<Object[]> foreignKeyRows = batches.getAllValues().stream()
                .flatMap(List::stream)
                .filter(row -> row.length == 2 && row[1] instanceof Integer)
                .toList();

        assertThat(foreignKeyRows).isNotEmpty();
        assertThat(foreignKeyRows).allSatisfy(row ->
                assertThat((Integer) row[1]).isBetween(1, 50));
    }

    @Test
    @DisplayName("a foreign key takes the parent's stored values, so text keys match")
    @SuppressWarnings("unchecked")
    void foreignKeyUsesStoredParentValues() {
        org.mockito.Mockito.when(jdbcTemplate.queryForList(
                        org.mockito.ArgumentMatchers.contains("`grade_scale`"), org.mockito.ArgumentMatchers.eq(Object.class)))
                .thenReturn(List.of("A", "B"));
        ParsedTable scale = new ParsedTable("grade_scale",
                List.of(new ParsedColumn("letter", "VARCHAR", List.of("255"), true, false, false, true)), List.of());
        ParsedTable enrollments = new ParsedTable("enrollments",
                List.of(pk("id"), col("grade_letter", "VARCHAR")),
                List.of(new KnownSchema.ForeignKey("enrollments", "grade_letter", "grade_scale", "letter")));

        seeder.seed(new ParsedSchema(List.of(scale, enrollments), List.of()));

        ArgumentCaptor<List<Object[]>> batches = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                org.mockito.ArgumentMatchers.contains("`enrollments`"), batches.capture());
        assertThat(batches.getAllValues().stream().flatMap(List::stream).map(row -> row[1]).distinct().toList())
                .isNotEmpty().allSatisfy(v -> assertThat(v).isIn("A", "B"));
    }

    @Test
    @DisplayName("a two-column primary key never repeats a pair")
    @SuppressWarnings("unchecked")
    void compositeKeyPairsAreUnique() {
        org.mockito.Mockito.when(jdbcTemplate.queryForList(
                        org.mockito.ArgumentMatchers.contains("`students`"), org.mockito.ArgumentMatchers.eq(Object.class)))
                .thenReturn(List.of(1, 2, 3));
        org.mockito.Mockito.when(jdbcTemplate.queryForList(
                        org.mockito.ArgumentMatchers.contains("`clubs`"), org.mockito.ArgumentMatchers.eq(Object.class)))
                .thenReturn(List.of(1, 2, 3));
        ParsedTable students = new ParsedTable("students", List.of(pk("student_id")), List.of());
        ParsedTable clubs = new ParsedTable("clubs", List.of(pk("club_id")), List.of());
        ParsedTable memberships = new ParsedTable("memberships",
                List.of(new ParsedColumn("student_id", "INT", List.of(), true, false, false, true),
                        new ParsedColumn("club_id", "INT", List.of(), true, false, false, true)),
                List.of(new KnownSchema.ForeignKey("memberships", "student_id", "students", "student_id"),
                        new KnownSchema.ForeignKey("memberships", "club_id", "clubs", "club_id")));

        seeder.seed(new ParsedSchema(List.of(students, clubs, memberships), List.of()));

        ArgumentCaptor<List<Object[]>> batches = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                org.mockito.ArgumentMatchers.contains("`memberships`"), batches.capture());
        List<String> pairs = batches.getAllValues().stream().flatMap(List::stream)
                .map(row -> row[0] + "-" + row[1]).toList();
        assertThat(pairs).isNotEmpty().doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(9);
    }

    @Test
    @DisplayName("a unique foreign key takes each parent value once, and no more rows than the parent has")
    @SuppressWarnings("unchecked")
    void uniqueForeignKeyTakesEachParentOnce() {
        org.mockito.Mockito.when(jdbcTemplate.queryForList(
                        org.mockito.ArgumentMatchers.contains("`applications`"), org.mockito.ArgumentMatchers.eq(Object.class)))
                .thenReturn(List.of(4, 5, 6));
        ParsedTable applications = new ParsedTable("applications", List.of(pk("application_id")), List.of());
        ParsedTable offers = new ParsedTable("offers",
                List.of(pk("offer_id"), new ParsedColumn("application_id", "INT", List.of(), true, false, true, false)),
                List.of(new KnownSchema.ForeignKey("offers", "application_id", "applications", "application_id")));

        seeder.seed(new ParsedSchema(List.of(applications, offers), List.of()));

        ArgumentCaptor<List<Object[]>> batches = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                org.mockito.ArgumentMatchers.contains("`offers`"), batches.capture());
        assertThat(batches.getAllValues().stream().flatMap(List::stream).map(row -> row[1]).toList())
                .containsExactlyInAnyOrder(4, 5, 6);
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
