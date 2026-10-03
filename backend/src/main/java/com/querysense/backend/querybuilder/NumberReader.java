package com.querysense.backend.querybuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads a whole number written in digits or English words at the start of a phrase. */
public final class NumberReader {

    public record Reading(BigDecimal value, String phrase) {
        public boolean integral() {
            return value.stripTrailingZeros().scale() <= 0;
        }
    }

    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");

    private static final Map<String, Integer> UNITS = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "six", 6, "seven", 7, "eight", 8, "nine", 9);

    private static final Map<String, Integer> TEENS = Map.of(
            "ten", 10, "eleven", 11, "twelve", 12, "thirteen", 13, "fourteen", 14,
            "fifteen", 15, "sixteen", 16, "seventeen", 17, "eighteen", 18, "nineteen", 19);

    private static final Map<String, Integer> TENS = Map.of(
            "twenty", 20, "thirty", 30, "forty", 40, "fifty", 50,
            "sixty", 60, "seventy", 70, "eighty", 80, "ninety", 90);

    private static final Map<String, Long> SCALES = Map.of("thousand", 1_000L, "million", 1_000_000L);

    private static final Set<String> OTHER_WORDS = Set.of("zero", "a", "and", "hundred", "dozen");

    private NumberReader() {}

    public static Optional<Reading> leading(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] tokens = text.trim().split("\\s+");
        if (DIGITS.matcher(tokens[0]).matches()) {
            return Optional.of(new Reading(new BigDecimal(tokens[0].replace(",", "")), tokens[0]));
        }
        int usable = 0;
        while (usable < tokens.length && Arrays.stream(tokens[usable].split("-")).allMatch(NumberReader::isNumberWord)) {
            usable++;
        }
        for (int end = usable; end >= 1; end--) {
            List<String> words = new ArrayList<>();
            for (int i = 0; i < end; i++) {
                words.addAll(List.of(tokens[i].split("-")));
            }
            Optional<Long> value = valueOf(words);
            if (value.isPresent()) {
                return Optional.of(new Reading(BigDecimal.valueOf(value.get()),
                        String.join(" ", Arrays.copyOfRange(tokens, 0, end))));
            }
        }
        return Optional.empty();
    }

    private static boolean isNumberWord(String word) {
        return UNITS.containsKey(word) || TEENS.containsKey(word) || TENS.containsKey(word)
                || SCALES.containsKey(word) || OTHER_WORDS.contains(word);
    }

    private static Optional<Long> valueOf(List<String> words) {
        if (words.size() == 1 && "zero".equals(words.get(0))) {
            return Optional.of(0L);
        }
        long total = 0;
        long current = 0;
        long lastScale = Long.MAX_VALUE;
        String prev = null;
        boolean any = false;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            boolean last = i == words.size() - 1;
            if ("a".equals(word)) {
                if (i != 0 || last || !Set.of("hundred", "thousand", "million", "dozen").contains(words.get(1))) {
                    return Optional.empty();
                }
                current = 1;
            } else if ("and".equals(word)) {
                if (last || !("hundred".equals(prev) || SCALES.containsKey(prev))) {
                    return Optional.empty();
                }
            } else if (UNITS.containsKey(word)) {
                if (!(prev == null || TENS.containsKey(prev) || "hundred".equals(prev) || "and".equals(prev)
                        || SCALES.containsKey(prev))) {
                    return Optional.empty();
                }
                current += UNITS.get(word);
                any = true;
            } else if (TEENS.containsKey(word) || TENS.containsKey(word)) {
                if (!(prev == null || "hundred".equals(prev) || "and".equals(prev) || SCALES.containsKey(prev))) {
                    return Optional.empty();
                }
                current += TEENS.containsKey(word) ? TEENS.get(word) : TENS.get(word);
                any = true;
            } else if ("hundred".equals(word)) {
                if (!(UNITS.containsKey(prev) || "a".equals(prev))) {
                    return Optional.empty();
                }
                current *= 100;
                any = true;
            } else if ("dozen".equals(word)) {
                if (!last || !("a".equals(prev) || UNITS.containsKey(prev))) {
                    return Optional.empty();
                }
                current *= 12;
                any = true;
            } else if (SCALES.containsKey(word)) {
                long scale = SCALES.get(word);
                if (current == 0 || scale >= lastScale) {
                    return Optional.empty();
                }
                total += current * scale;
                current = 0;
                lastScale = scale;
                any = true;
            } else {
                return Optional.empty();
            }
            prev = word;
        }
        return any ? Optional.of(total + current) : Optional.empty();
    }
}
