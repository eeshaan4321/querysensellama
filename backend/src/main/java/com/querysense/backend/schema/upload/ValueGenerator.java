package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.ddl.ParsedColumn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * ValueGenerator
 *
 * Produces a plausible value for one column of one row.
 *
 * WHY THE COLUMN NAME STEERS THE VALUE, NOT JUST THE TYPE:
 * Two reasons, and neither is cosmetic. First,
 * SchemaMetadataService.getDistinctValues is the rule engine's live-data
 * safety net — it reads the actual values in a column to notice when a user
 * names one ("customers in Mumbai"). Fed random hex strings it can never
 * fire, and the engine starts claiming queries it should have deferred.
 * Second, a column called `status` holding 500 distinct values is not what
 * a status column looks like, and EXPLAIN's `filtered` estimate is computed
 * from exactly that distribution.
 *
 * WHY CARDINALITY IS DELIBERATELY SKEWED:
 * Uniform data makes every `filtered` estimate 100.00 and every index
 * equally selective, which teaches EXPLAIN nothing. A status-like column
 * gets a handful of distinct values; an email-like one gets one per row.
 * The analysis modules then have something real to disagree about.
 *
 * WHY A SEEDED Random:
 * A demo that reports different row estimates on every upload of the same
 * file is not one anyone can trust or re-run. Seeding makes the generated
 * data reproducible across machines.
 *
 * HONESTY NOTE: this data is generated. It gives EXPLAIN a realistic
 * MAGNITUDE, which an empty table cannot, but the distribution is invented.
 * The UI labels an uploaded schema's plan accordingly — see PROJECT.md R6.
 */
public class ValueGenerator {

    private static final List<String> FIRST_NAMES = List.of(
            "Aarav", "Diya", "Rohan", "Isha", "Kabir", "Meera", "Arjun", "Sana",
            "Vikram", "Nisha", "Rahul", "Priya", "Karan", "Anaya", "Dev", "Tara");
    private static final List<String> LAST_NAMES = List.of(
            "Sharma", "Patel", "Nair", "Reddy", "Iyer", "Gupta", "Bose", "Khan",
            "Menon", "Rao", "Desai", "Joshi");
    private static final List<String> CITIES = List.of(
            "Mumbai", "Pune", "Delhi", "Bengaluru", "Hyderabad", "Chennai",
            "Kolkata", "Ahmedabad", "Jaipur", "Surat");
    private static final List<String> STATUSES = List.of(
            "NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED");
    private static final List<String> METHODS = List.of("CARD", "UPI", "NETBANKING", "CASH");
    private static final List<String> DOMAINS = List.of(
            "example.com", "mail.test", "shop.local", "corp.example");
    private static final List<String> WORDS = List.of(
            "alpha", "bravo", "delta", "echo", "gamma", "helix", "ionic", "kappa",
            "lumen", "nova", "orbit", "prism", "quartz", "raven", "sigma", "tempo");

    /** Nullable, non-key columns get this share of NULLs so `filtered` varies. */
    private static final double NULL_RATE = 0.10;

    private final Random random;

    public ValueGenerator(long seed) {
        this.random = new Random(seed);
    }

    public Object valueFor(ParsedColumn column, int rowIndex, int totalRows) {
        if (isNullable(column) && random.nextDouble() < NULL_RATE) {
            return null;
        }

        return switch (column.normalisedType()) {
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT" ->
                    integerValue(column, rowIndex);
            case "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "REAL" -> decimalValue(column);
            case "DATE" -> java.sql.Date.valueOf(dateValue());
            case "DATETIME", "TIMESTAMP" ->
                    java.sql.Timestamp.valueOf(dateValue().atStartOfDay().plusHours(random.nextInt(24)));
            case "TIME" -> java.sql.Time.valueOf(LocalTime.of(random.nextInt(24), random.nextInt(60)));
            case "BOOLEAN", "BOOL", "BIT" -> random.nextBoolean();
            case "ENUM", "SET" -> enumValue(column);
            default -> textValue(column, rowIndex);
        };
    }

    /**
     * A key column must never be null, and a NOT NULL column obviously must
     * not be either. Everything else is fair game — and should be, because
     * an all-populated column makes MySQL report filtered=100.00.
     */
    private boolean isNullable(ParsedColumn column) {
        return !column.notNull() && !column.primaryKey() && !column.unique() && !column.autoIncrement();
    }

    /**
     * Sequential for anything that must be unique, so a primary key is a
     * dense 1..n range — which is also what makes foreign keys cheap to
     * generate: SchemaSeeder can pick a parent id uniformly from that range
     * without reading anything back from the database.
     */
    private Object integerValue(ParsedColumn column, int rowIndex) {
        if (column.primaryKey() || column.autoIncrement() || column.unique()) {
            return rowIndex + 1;
        }
        String name = column.name().toLowerCase(Locale.ROOT);
        if (name.contains("quantity") || name.contains("qty")) {
            return 1 + random.nextInt(20);
        }
        if (name.contains("year")) {
            return 2015 + random.nextInt(11);
        }
        return 1 + random.nextInt(1000);
    }

    private BigDecimal decimalValue(ParsedColumn column) {
        int precision = column.typeArgs().isEmpty() ? 10 : column.firstArgOr(10);
        int scale = column.typeArgs().size() > 1 ? parseOr(column.typeArgs().get(1), 2) : 2;
        int wholeDigits = Math.max(1, precision - scale);
        long bound = (long) Math.pow(10, Math.min(wholeDigits, 9));
        double raw = random.nextDouble() * bound;
        return BigDecimal.valueOf(raw).setScale(scale, RoundingMode.HALF_UP);
    }

    /** Spread over two years so date-range filters have something to select. */
    private LocalDate dateValue() {
        return LocalDate.now().minusDays(random.nextInt(730));
    }

    private String enumValue(ParsedColumn column) {
        if (column.typeArgs().isEmpty()) {
            return pick(STATUSES);
        }
        String raw = column.typeArgs().get(random.nextInt(column.typeArgs().size()));
        return raw.trim().replaceAll("^'|'$", "");
    }

    /**
     * The name-driven branch. Order matters: `email` is checked before
     * `name`, because a column called `email_name` is far likelier to hold
     * an address than a person.
     */
    private String textValue(ParsedColumn column, int rowIndex) {
        int max = column.typeArgs().isEmpty() ? 255 : column.firstArgOr(255);
        String name = column.name().toLowerCase(Locale.ROOT);
        boolean mustBeUnique = column.unique() || column.primaryKey();

        String value;
        if (name.contains("email") || name.contains("mail")) {
            value = (pick(FIRST_NAMES) + "." + pick(LAST_NAMES) + rowIndex).toLowerCase(Locale.ROOT)
                    + "@" + pick(DOMAINS);
        } else if (name.contains("phone") || name.contains("mobile") || name.contains("contact")) {
            value = "+91" + (7000000000L + random.nextInt(999999999));
        } else if (name.contains("city") || name.contains("town")) {
            value = pick(CITIES);
        } else if (name.contains("status") || name.contains("state")) {
            value = pick(STATUSES);
        } else if (name.contains("method") || name.contains("mode") || name.contains("type")) {
            value = pick(METHODS);
        } else if (name.contains("name") || name.contains("title")) {
            value = pick(FIRST_NAMES) + " " + pick(LAST_NAMES);
        } else {
            value = pick(WORDS) + "-" + random.nextInt(10000);
        }

        if (mustBeUnique) {
            value = withUniqueSuffix(value, rowIndex, max);
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * A UNIQUE column must not collide, and truncating to a declared length
     * can create collisions the generator never intended — two long names
     * sharing a prefix become the same value at VARCHAR(8). The row index is
     * appended and the PREFIX is trimmed to make room, so the part that
     * guarantees uniqueness always survives.
     */
    private String withUniqueSuffix(String value, int rowIndex, int max) {
        String suffix = "-" + rowIndex;
        if (suffix.length() >= max) {
            return String.valueOf(rowIndex);
        }
        int room = max - suffix.length();
        String prefix = value.length() > room ? value.substring(0, room) : value;
        return prefix + suffix;
    }

    private String pick(List<String> pool) {
        return pool.get(random.nextInt(pool.size()));
    }

    private int parseOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
