package com.querysense.backend.querybuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The built-in English synonym groups the rule engine may use to reach a table or column. */
public final class Synonyms {

    private static final String RESOURCE = "/querybuilder/synonyms.json";

    private final List<Set<String>> groups;

    public Synonyms(List<List<String>> groups) {
        this.groups = groups.stream()
                .map(g -> {
                    Set<String> words = new LinkedHashSet<>();
                    g.forEach(w -> words.add(w.toLowerCase(Locale.ROOT).trim()));
                    return Set.copyOf(words);
                })
                .toList();
    }

    public static Synonyms builtIn() {
        return new Synonyms(builtInGroups());
    }

    static List<List<String>> builtInGroups() {
        try (InputStream in = Synonyms.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new ObjectMapper().readValue(in, new TypeReference<List<List<String>>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    public Set<String> of(String word) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> group : groups) {
            if (group.contains(word)) {
                group.stream().filter(w -> !w.equals(word)).forEach(out::add);
            }
        }
        return out;
    }

    public Set<String> allWords() {
        Set<String> out = new LinkedHashSet<>();
        groups.forEach(out::addAll);
        return out;
    }
}
