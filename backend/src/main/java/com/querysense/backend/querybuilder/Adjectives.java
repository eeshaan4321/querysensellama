package com.querysense.backend.querybuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The reviewed adjectives a ranking question may use, each with the column words it reaches and a direction. */
public final class Adjectives {

    public record Entry(List<String> words, QueryIntent.Direction direction, boolean needsMostOrLeast, String type) {}

    private static final String RESOURCE = "/querybuilder/adjectives.json";

    private final Map<String, Entry> entries;

    public Adjectives(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static Adjectives builtIn() {
        try (InputStream in = Adjectives.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            Map<String, Entry> read = new ObjectMapper().readValue(in, new TypeReference<LinkedHashMap<String, Entry>>() {});
            return new Adjectives(read);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    public Map<String, Entry> entries() {
        return entries;
    }
}
