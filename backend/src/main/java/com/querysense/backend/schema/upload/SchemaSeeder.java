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

/** Fills uploaded tables with generated rows. */
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
                        @Value("${querysense.schema.upload.seed-rows:100}") int seedRows,
                        @Value("${querysense.schema.upload.seed:20260910}") long seed) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadCatalog = uploadCatalog;
        this.seedRows = seedRows;
        this.seed = seed;
    }

    public record SeedReport(Map<String, Integer> rowCounts, List<String> warnings) {
    }

    public SeedReport seed(ParsedSchema schema) {
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<ParsedTable> ordered = orderTables(schema);

        for (ParsedTable table : ordered) {
            int target = (int) seedableRowsFor(table);
            TableSeedResult result = insertRows(table, target);
            rowCounts.put(table.name(), result.inserted());

            if (target < seedRows) {
                warnings.add("Table '" + table.name() + "' was seeded to " + target
                        + " rows rather than " + seedRows
                        + ", because a key or unique column is too narrow to hold more.");
            }
            if (result.inserted() < target) {
                warnings.add("Seeded only " + result.inserted() + " of " + target
                        + " rows into '" + table.name() + "'"
                        + (result.failure() == null ? "." : " -- " + result.failure())
                        + " Row estimates for this table describe what was actually inserted.");
            }
        }
        ordered.forEach(this::pointSelfReferencesAtEarlierRows);
        ordered.forEach(this::analyse);

        return new SeedReport(rowCounts, warnings);
    }

    long seedableRowsFor(ParsedTable table) {
        return table.columns().stream()
                .filter(c -> c.primaryKey() || c.unique() || c.autoIncrement())
                .mapToLong(c -> ValueGenerator.maxSeedableRows(c, seedRows))
                .min()
                .orElse(seedRows);
    }

    private record TableSeedResult(int inserted, String failure) {
    }

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

    private TableSeedResult insertRows(ParsedTable table, int targetRows) {
        List<ParsedColumn> columns = table.columns();
        String columnList = columns.stream().map(c -> "`" + c.name() + "`").collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO `" + uploadCatalog + "`.`" + table.name() + "` ("
                + columnList + ") VALUES (" + placeholders + ")";

        ValueGenerator generator = new ValueGenerator(seed + table.name().hashCode());
        Random fkPicker = new Random(seed + table.name().hashCode() + 1);

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int inserted = 0;
        String[] firstFailure = new String[1];

        for (int row = 0; row < targetRows; row++) {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = valueForColumn(table, columns.get(i), generator, fkPicker, row, targetRows);
            }
            batch.add(values);

            if (batch.size() == BATCH_SIZE || row == targetRows - 1) {
                inserted += flush(sql, batch, table.name(), firstFailure);
                batch.clear();
            }
        }
        return new TableSeedResult(inserted, firstFailure[0]);
    }

    private Object valueForColumn(ParsedTable table, ParsedColumn column,
                                  ValueGenerator generator, Random fkPicker, int row,
                                  int targetRows) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.fromColumn().equalsIgnoreCase(column.name())) {
                continue;
            }
            if (fk.toTable().equalsIgnoreCase(table.name())) {
                return null;
            }
            long ceiling = Math.min(targetRows, ValueGenerator.maxFor(column));
            return 1 + fkPicker.nextInt((int) Math.max(1, ceiling));
        }
        return generator.valueFor(column, row, targetRows);
    }

    private int flush(String sql, List<Object[]> batch, String tableName, String[] firstFailure) {
        try {
            jdbcTemplate.batchUpdate(sql, List.copyOf(batch));
            return batch.size();
        } catch (DataAccessException e) {
            log.warn("Could not seed a batch into '{}': {}", tableName, e.getMessage());
            if (firstFailure[0] == null) {
                firstFailure[0] = rootReason(e);
            }
            return 0;
        }
    }

    private String rootReason(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? e.getMessage() : message.trim();
    }

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

    private void analyse(ParsedTable table) {
        try {
            jdbcTemplate.execute("ANALYZE TABLE `" + uploadCatalog + "`.`" + table.name() + "`");
        } catch (DataAccessException e) {
            log.warn("ANALYZE TABLE failed for '{}': {}", table.name(), e.getMessage());
        }
    }
}
