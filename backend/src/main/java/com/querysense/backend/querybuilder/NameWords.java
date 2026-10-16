package com.querysense.backend.querybuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Splits any table or column name into lowercase words. */
public final class NameWords {

    private NameWords() {}

    public static List<String> words(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String spaced = name
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([A-Za-z])", "$1 $2");
        return Arrays.stream(spaced.split("[^A-Za-z0-9]+"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.toLowerCase(Locale.ROOT))
                .toList();
    }

    public static String phrase(String name) {
        return String.join(" ", words(name));
    }

    public static String compact(String phrase) {
        return phrase.replace(" ", "");
    }
}
