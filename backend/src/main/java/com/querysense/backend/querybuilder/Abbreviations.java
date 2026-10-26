package com.querysense.backend.querybuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The built-in short forms found in column names, and the words each stands for. */
public final class Abbreviations {

    private static final String RESOURCE = "/querybuilder/abbreviations.json";

    private static final Abbreviations BUILT_IN = new Abbreviations(load());

    private final Map<String, String> expansions;

    public Abbreviations(Map<String, String> expansions) {
        this.expansions = Map.copyOf(expansions);
    }

    public static Abbreviations builtIn() {
        return BUILT_IN;
    }

    private static Map<String, String> load() {
        try (InputStream in = Abbreviations.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    public List<String> expand(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String word : words) {
            String full = expansions.get(word);
            out.addAll(full == null ? List.of(word) : List.of(full.split(" ")));
        }
        return out;
    }
}
