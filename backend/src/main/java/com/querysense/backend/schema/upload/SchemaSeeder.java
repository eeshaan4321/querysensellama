package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SchemaSeeder
 *
 * WHY SEEDING IS PART OF THE UPLOAD AND NOT AN OPTIONAL EXTRA:
 * PROJECT.md records the measurement: on an empty table of identical shape
 * MySQL reports rows=1, filtered=100.00, where the populated table reports
 * rows=15, filtered=10.00. Same access path, useless magnitude. An uploaded
 * schema with no rows would let EXPLAIN run and let every module downstream
 * of it produce output that looks fine and means nothing — the worst
 * failure shape this project has, because nothing on screen would say so.
 * Creating the tables is half the feature; filling them is the other half.
 *
 * WHY 500 ROWS BY DEFAULT AND NOT 25:
 * The built-in demo tables hold 5-49 rows, and at that scale every domain
 * suggestion ends with "the estimated row count is low, so the performance
 * impact is likely minor" — the engine can identify a problem but never
 * show one costing anything. At 500 an unindexed scan has a magnitude worth
 * reporting.
 *
 * WHY TOPOLOGICAL ORDER:
 * Foreign keys are real constraints in the provisioned schema, so a child
 * row inserted before its parent is rejected outright. Parents first.
 *
 * WHY A CYCLE IS TOLERATED RATHER THAN REJECTED:
 * employees.manager_id -> employees.id is an ordinary, sensible schema. The
 * self-reference is seeded NULL on the way in and pointed at an earlier row
 * afterwards. A mutual cycle between two tables cannot be resolved that
 * way, so those tables are appended in declaration order and MySQL is left
 * to accept what it can — one sparsely-filled table beats refusing the
 * user's whole schema over a shape their database already runs.
 */
@Component
public class SchemaSeeder {

    private static final Logger log = LoggerFactory.getLogger(SchemaSeeder.class);
    private static final int BATCH_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;
    private final String uploadCatalog;
    private final int seedRows;
    private final long seed;

    public SchemaSeeder(JdbcTemplate jdbcTemplate,
                        @Value("${querysense.schema.upload.catalog:querysense_uploads}") String uploadCatalog,
                        @Value("${querysense.schema.upload.seed-rows:500}") int seedRows,
                        @Value("${querysense.schema.upload.seed:20260910}") long seed) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadCatalog = uploadCatalog;
        this.seedRows = seedRows;
        this.seed = seed;
    }

    /** @return rows actually inserted, per table. */
    public Map<String, Integer> seed(ParsedSchema schema) {
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        List<ParsedTable> ordered = orderTables(schema);

        for (ParsedTable table : ordered) {
            rowCounts.put(table.name(), insertRows(table));
        }
        ordered.forEach(this::pointSelfReferencesAtEarlierRows);
        ordered.forEach(this::analyse);

        return rowCounts;
    }

    /**
     * Kahn's algorithm over the foreign-key graph. Self-references are
     * excluded from the edge set — a table does not have to exist before
     * itself — and anything still unresolved after the queue drains (a
     * mutual cycle) is appended in declaration order rather than dropped.
     */
    List<ParsedTable> orderTables(ParsedSchema schema) {
        Map<String, ParsedTable> byName = schema.tables().stream()
                .collect(Collectors.toMap(
                        t -> t.name().toLowerCase(Locale.ROOT), t -> t, (a, b) -> a, LinkedHashMap::new));

        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        byName.keySet().forEach(name -> dependsOn.put(name, new LinkedHashSet<>()));

        for (ParsedTable table : schema.tables()) {
            String from = table.name().toLowerCase(Locale.ROOT);
            for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
                String to = fk.toTable().toLowerCase(Locale.ROOT);
                if (!to.equals(from) && byName.containsKey(to)) {
                    dependsOn.get(from).add(to);
                }
            }
        }

        List<ParsedTable> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        boolean progressed = true;

        while (progressed && placed.size() < byName.size()) {
            progressed = false;
            for (Map.Entry<String, Set<String>> entry : dependsOn.entrySet()) {
                if (placed.contains(entry.getKey())) {
                    continue;
                }
                if (placed.containsAll(entry.getValue())) {
                    ordered.add(byName.get(entry.getKey()));
                    placed.add(entry.getKey());
                    progressed = true;
                }
            }
        }

        for (Map.Entry<String, ParsedTable> entry : byName.entrySet()) {
            if (!placed.contains(entry.getKey())) {
                log.warn("Table '{}' is part of a foreign-key cycle; seeding it in declaration order.",
                        entry.getValue().name());
                ordered.add(entry.getValue());
            }
        }
        return ordered;
    }

    private int insertRows(ParsedTable table) {
        List<ParsedColumn> columns = table.columns();
        String columnList = columns.stream().map(c -> "`" + c.name() + "`").collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO `" + uploadCatalog + "`.`" + table.name() + "` ("
                + columnList + ") VALUES (" + placeholders + ")";

        // Per-table seeds, so adding a table to an upload does not shift the
        // data generated for every table after it.
        ValueGenerator generator = new ValueGenerator(seed + table.name().hashCode());
        Random fkPicker = new Random(seed + table.name().hashCode() + 1);

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int inserted = 0;

        for (int row = 0; row < seedRows; row++) {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = valueForColumn(table, columns.get(i), generator, fkPicker, row);
            }
            batch.add(values);

            if (batch.size() == BATCH_SIZE || row == seedRows - 1) {
                inserted += flush(sql, batch, table.name());
                batch.clear();
            }
        }
        return inserted;
    }

    /**
     * A foreign key column must hold an id that actually exists in the
     * parent. Parents are seeded first, and ValueGenerator gives every key
     * column a dense sequential 1..seedRows range, so picking uniformly from
     * that range is both valid and cheap — no read back from the database is
     * needed at all.
     */
    private Object valueForColumn(ParsedTable table, ParsedColumn column,
                                  ValueGenerator generator, Random fkPicker, int row) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.fromColumn().equalsIgnoreCase(column.name())) {
                continue;
            }
            // Self-reference: NULL now, repaired once the table is full.
            if (fk.toTable().equalsIgnoreCase(table.name())) {
                return null;
            }
            return 1 + fkPicker.nextInt(seedRows);
        }
        return generator.valueFor(column, row, seedRows);
    }

    private int flush(String sql, List<Object[]> batch, String tableName) {
        try {
            jdbcTemplate.batchUpdate(sql, List.copyOf(batch));
            return batch.size();
        } catch (DataAccessException e) {
            log.warn("Could not seed a batch into '{}': {}", tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * The second pass for self-references. Row N is pointed at a row before
     * it, so the graph stays acyclic and no row is its own parent — which
     * MySQL would accept but which makes a nonsense of any hierarchy query
     * run against the result.
     */
    private void pointSelfReferencesAtEarlierRows(ParsedTable table) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.toTable().equalsIgnoreCase(table.name())) {
                continue;
            }
            String sql = "UPDATE `" + uploadCatalog + "`.`" + table.name() + "`"
                    + " SET `" + fk.fromColumn() + "` = GREATEST(1, FLOOR(`" + fk.toColumn() + "` / 2))"
                    + " WHERE `" + fk.toColumn() + "` > 1";
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException e) {
                log.warn("Could not populate self-reference {}.{}: {}",
                        table.name(), fk.fromColumn(), e.getMessage());
            }
        }
    }

    /**
     * Without this, InnoDB's cardinality statistics can still reflect the
     * empty table, and EXPLAIN's `rows` estimate is then noise — which is
     * the exact failure this whole class exists to prevent. Seeding without
     * analysing would look like it worked and wouldn't have.
     */
    private void analyse(ParsedTable table) {
        try {
            jdbcTemplate.execute("ANALYZE TABLE `" + uploadCatalog + "`.`" + table.name() + "`");
        } catch (DataAccessException e) {
            log.warn("ANALYZE TABLE failed for '{}': {}", table.name(), e.getMessage());
        }
    }
}
