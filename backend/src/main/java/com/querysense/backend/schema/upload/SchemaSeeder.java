package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import com.querysense.backend.schema.ddl.SampleInsert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Fills uploaded tables with generated rows. */
@Component
public class SchemaSeeder implements SchemaSeeding {

    private static final Logger log = LoggerFactory.getLogger(SchemaSeeder.class);
    private static final int BATCH_SIZE = 200;
    private static final int KEY_ATTEMPTS = 20;
    private static final Set<String> INTEGER_TYPES = Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT");
    private static final int PARENT_VALUE_LIMIT = 20_000;

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

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public SeedReport seed(ParsedSchema schema) {
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<ParsedTable> ordered = orderTables(schema);

        Map<String, Integer> firstGenerated = new HashMap<>();

        for (ParsedTable table : ordered) {
            int target = (int) seedableRowsFor(table);
            int fromFile = insertFileRows(table, schema.samplesFor(table.name()), warnings);
            int start = fromFile == 0 ? 0 : Math.max(fromFile, maxIntegerKey(table));
            TableSeedResult result = insertRows(table, Math.max(0, target - fromFile), start);
            int inserted = fromFile + result.inserted();
            rowCounts.put(table.name(), inserted);
            firstGenerated.put(table.name(), start);

            if (target < seedRows) {
                warnings.add("Table '" + table.name() + "' was seeded to " + target
                        + " rows rather than " + seedRows
                        + ", because a key or unique column is too narrow to hold more.");
            }
            if (inserted < target) {
                warnings.add("Seeded only " + inserted + " of " + target
                        + " rows into '" + table.name() + "'"
                        + (result.failure() == null ? "." : " -- " + result.failure())
                        + " Row estimates for this table describe what was actually inserted.");
            }
        }
        ordered.forEach(t -> pointSelfReferencesAtEarlierRows(t, firstGenerated.getOrDefault(t.name(), 0)));
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

    private int insertFileRows(ParsedTable table, List<SampleInsert> samples, List<String> warnings) {
        int inserted = 0;
        for (SampleInsert sample : samples) {
            List<String> named = sample.columns().isEmpty() ? table.columnNames() : sample.columns();
            if (sample.columns().isEmpty() && sample.rows().stream().anyMatch(r -> r.size() != named.size())) {
                warnings.add("Did not load the file's rows for '" + table.name() + "': they list no columns and "
                        + "their values don't line up with the table's columns.");
                continue;
            }
            List<Integer> kept = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < named.size(); i++) {
                Optional<ParsedColumn> column = table.column(named.get(i));
                if (column.isPresent()) {
                    columns.add(column.get().name());
                    kept.add(i);
                }
            }
            if (columns.isEmpty()) {
                continue;
            }
            List<ParsedColumn> missing = table.columns().stream()
                    .filter(c -> c.notNull() && !c.autoIncrement())
                    .filter(c -> columns.stream().noneMatch(name -> name.equalsIgnoreCase(c.name())))
                    .toList();
            missing.forEach(c -> columns.add(c.name()));
            ValueGenerator filler = new ValueGenerator(seed + table.name().hashCode() + 2);
            Random fillerPicker = new Random(seed + table.name().hashCode() + 3);
            Map<String, List<Object>> parents = missing.isEmpty() ? Map.of() : parentValuesFor(table);
            String sql = "INSERT INTO `" + uploadCatalog + "`.`" + table.name() + "` ("
                    + columns.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", ")) + ") VALUES ("
                    + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
            List<Object[]> batch = new ArrayList<>();
            String[] failure = new String[1];
            for (List<Object> row : sample.rows()) {
                if (row.size() != named.size()) {
                    continue;
                }
                List<Object> values = new ArrayList<>(kept.stream().map(row::get).toList());
                int rowIndex = inserted + batch.size();
                for (ParsedColumn column : missing) {
                    List<Object> stored = parents.get(column.name().toLowerCase(Locale.ROOT));
                    values.add(stored != null ? stored.get(fillerPicker.nextInt(stored.size()))
                            : filler.valueFor(column, rowIndex, sample.rows().size()));
                }
                batch.add(values.toArray());
                if (batch.size() == BATCH_SIZE) {
                    inserted += flush(sql, batch, table.name(), failure);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                inserted += flush(sql, batch, table.name(), failure);
            }
            if (failure[0] != null) {
                warnings.add("Some of the file's rows for '" + table.name() + "' could not be loaded -- " + failure[0]);
            }
        }
        return inserted;
    }

    private int maxIntegerKey(ParsedTable table) {
        List<ParsedColumn> keys = table.columns().stream().filter(ParsedColumn::primaryKey).toList();
        if (keys.size() != 1 || !INTEGER_TYPES.contains(keys.get(0).normalisedType())) {
            return 0;
        }
        try {
            Long max = jdbcTemplate.queryForObject("SELECT MAX(`" + keys.get(0).name() + "`) FROM `"
                    + uploadCatalog + "`.`" + table.name() + "`", Long.class);
            return max == null ? 0 : (int) Math.min(Integer.MAX_VALUE, max);
        } catch (DataAccessException e) {
            return 0;
        }
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

    private TableSeedResult insertRows(ParsedTable table, int count, int start) {
        List<ParsedColumn> columns = table.columns();
        String columnList = columns.stream().map(c -> "`" + c.name() + "`").collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO `" + uploadCatalog + "`.`" + table.name() + "` ("
                + columnList + ") VALUES (" + placeholders + ")";

        ValueGenerator generator = new ValueGenerator(seed + table.name().hashCode());
        Random fkPicker = new Random(seed + table.name().hashCode() + 1);
        Map<String, List<Object>> parentValues = parentValuesFor(table);
        List<Integer> keyColumns = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primaryKey()) {
                keyColumns.add(i);
            }
        }
        Set<List<Object>> usedKeys = new HashSet<>();
        if (start > 0 && keyColumns.size() >= 2) {
            usedKeys.addAll(existingKeys(table, keyColumns));
        }
        Set<String> oneEach = new HashSet<>();
        for (ParsedColumn column : columns) {
            String name = column.name().toLowerCase(Locale.ROOT);
            boolean singleKey = column.unique() || (column.primaryKey() && keyColumns.size() == 1);
            if (singleKey && parentValues.containsKey(name)) {
                List<Object> available = new ArrayList<>(parentValues.get(name));
                if (start > 0) {
                    available.removeAll(existingValues(table, column));
                }
                parentValues.put(name, available);
                oneEach.add(name);
                count = Math.min(count, available.size());
            }
        }
        int targetRows = start + count;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int inserted = 0;
        String[] firstFailure = new String[1];

        for (int row = start; row < targetRows; row++) {
            Object[] values = null;
            for (int attempt = 0; attempt < KEY_ATTEMPTS && values == null; attempt++) {
                Object[] candidate = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    candidate[i] = valueForColumn(table, columns.get(i), generator, fkPicker, row, targetRows,
                            parentValues, oneEach, row - start);
                }
                if (keyColumns.size() < 2 || usedKeys.add(keyColumns.stream().map(i -> candidate[i]).toList())) {
                    values = candidate;
                }
            }
            if (values != null) {
                batch.add(values);
            }

            if (!batch.isEmpty() && (batch.size() == BATCH_SIZE || row == targetRows - 1)) {
                inserted += flush(sql, batch, table.name(), firstFailure);
                batch.clear();
            }
        }
        return new TableSeedResult(inserted, firstFailure[0]);
    }

    private Map<String, List<Object>> parentValuesFor(ParsedTable table) {
        Map<String, List<Object>> values = new HashMap<>();
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (fk.toTable().equalsIgnoreCase(table.name())) {
                continue;
            }
            String sql = "SELECT DISTINCT `" + fk.toColumn() + "` FROM `" + uploadCatalog + "`.`" + fk.toTable()
                    + "` WHERE `" + fk.toColumn() + "` IS NOT NULL LIMIT " + PARENT_VALUE_LIMIT;
            try {
                List<Object> found = jdbcTemplate.queryForList(sql, Object.class);
                if (found != null && !found.isEmpty()) {
                    values.put(fk.fromColumn().toLowerCase(Locale.ROOT), found);
                }
            } catch (DataAccessException e) {
                log.warn("Could not read parent values {}.{}: {}", fk.toTable(), fk.toColumn(), e.getMessage());
            }
        }
        return values;
    }

    private Object valueForColumn(ParsedTable table, ParsedColumn column,
                                  ValueGenerator generator, Random fkPicker, int row,
                                  int targetRows, Map<String, List<Object>> parentValues, Set<String> oneEach,
                                  int sequence) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.fromColumn().equalsIgnoreCase(column.name())) {
                continue;
            }
            if (fk.toTable().equalsIgnoreCase(table.name())) {
                return null;
            }
            String name = column.name().toLowerCase(Locale.ROOT);
            List<Object> stored = parentValues.get(name);
            if (stored != null && oneEach.contains(name)) {
                return stored.get(sequence);
            }
            if (stored != null) {
                return stored.get(fkPicker.nextInt(stored.size()));
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

    private List<List<Object>> existingKeys(ParsedTable table, List<Integer> keyColumns) {
        String names = keyColumns.stream().map(i -> "`" + table.columns().get(i).name() + "`")
                .collect(Collectors.joining(", "));
        try {
            return jdbcTemplate.query("SELECT " + names + " FROM `" + uploadCatalog + "`.`" + table.name() + "`",
                    (rs, n) -> {
                        List<Object> key = new ArrayList<>();
                        for (int i = 1; i <= keyColumns.size(); i++) {
                            key.add(rs.getObject(i));
                        }
                        return key;
                    });
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    private List<Object> existingValues(ParsedTable table, ParsedColumn column) {
        try {
            return jdbcTemplate.queryForList("SELECT DISTINCT `" + column.name() + "` FROM `" + uploadCatalog + "`.`"
                    + table.name() + "` WHERE `" + column.name() + "` IS NOT NULL", Object.class);
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    private void pointSelfReferencesAtEarlierRows(ParsedTable table, int firstGenerated) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.toTable().equalsIgnoreCase(table.name())) {
                continue;
            }
            String sql = "UPDATE `" + uploadCatalog + "`.`" + table.name() + "`"
                    + " SET `" + fk.fromColumn() + "` = GREATEST(1, FLOOR(`" + fk.toColumn() + "` / 2))"
                    + " WHERE `" + fk.toColumn() + "` > " + Math.max(1, firstGenerated);
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
