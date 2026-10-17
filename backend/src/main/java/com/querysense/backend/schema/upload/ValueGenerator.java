package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.ddl.ParsedColumn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Generates column values for seeding. */
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

    private boolean isNullable(ParsedColumn column) {
        return !column.notNull() && !column.primaryKey() && !column.unique() && !column.autoIncrement();
    }

    private static final Map<String, Long> SIGNED_MAX = Map.of(
            "TINYINT", 127L,
            "SMALLINT", 32_767L,
            "MEDIUMINT", 8_388_607L,
            "INT", 2_147_483_647L,
            "INTEGER", 2_147_483_647L,
            "BIGINT", Long.MAX_VALUE);

    static long maxFor(ParsedColumn column) {
        return SIGNED_MAX.getOrDefault(column.normalisedType(), Long.MAX_VALUE);
    }

    public static long maxSeedableRows(ParsedColumn primaryKey, long desiredRows) {
        if (primaryKey == null) {
            return desiredRows;
        }
        return Math.min(desiredRows, maxFor(primaryKey));
    }

    private Object integerValue(ParsedColumn column, int rowIndex) {
        if (column.primaryKey() || column.autoIncrement() || column.unique()) {
            return rowIndex + 1;
        }
        String name = column.name().toLowerCase(Locale.ROOT);
        if (name.contains("quantity") || name.contains("qty")) {
            return clampToType(1 + random.nextInt(20), column);
        }
        if (name.contains("year")) {
            return clampToType(2015 + random.nextInt(11), column);
        }
        return clampToType(1 + random.nextInt(1000), column);
    }

    private long clampToType(long value, ParsedColumn column) {
        long max = maxFor(column);
        if (value <= max) {
            return value;
        }
        return 1 + Math.floorMod(value - 1, max);
    }

    private BigDecimal decimalValue(ParsedColumn column) {
        int precision = column.typeArgs().isEmpty() ? 10 : column.firstArgOr(10);
        int scale = column.typeArgs().size() > 1 ? parseOr(column.typeArgs().get(1), 2) : 2;
        int wholeDigits = Math.max(1, precision - scale);
        long bound = (long) Math.pow(10, Math.min(wholeDigits, 9));
        double raw = random.nextDouble() * bound;
        return BigDecimal.valueOf(raw).setScale(scale, RoundingMode.DOWN);
    }

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
