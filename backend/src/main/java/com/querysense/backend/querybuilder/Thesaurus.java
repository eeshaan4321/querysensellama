package com.querysense.backend.querybuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** A precomputed slice of WordNet giving synonyms, kinds-of and verb-to-noun links, only where one meaning clearly dominates. */
public final class Thesaurus {

    static final double DOMINANT_SHARE = 0.5;

    private static final String BASE = "/querybuilder/thesaurus/";
    private static final Set<String> COMMAND_VERBS =
            Set.of("show", "list", "give", "find", "get", "display", "sort", "order", "group");
    private static volatile Thesaurus instance;

    private final Map<String, String> synsetOf = new HashMap<>();
    private final Map<String, List<String>> lemmasOf = new HashMap<>();
    private final Map<String, List<String>> parents = new HashMap<>();
    private final Map<String, Set<String>> verbNouns = new HashMap<>();
    private final Map<String, Set<String>> nounForms = new HashMap<>();
    private final Map<String, Set<String>> handVerbs = new HashMap<>();
    private final Map<String, Set<String>> handNouns = new HashMap<>();
    private final Set<String> denied = new HashSet<>();
    private final Set<String> instructionWords = QueryIntentParser.instructionWords();

    private Thesaurus() {
        for (String[] row : rows("senses.tsv")) {
            double share = Double.parseDouble(row[2]);
            if (share >= DOMINANT_SHARE) {
                synsetOf.put(row[0], row[1]);
                lemmasOf.computeIfAbsent(row[1], k -> new ArrayList<>()).add(row[0]);
            }
        }
        for (String[] row : rows("parents.tsv")) {
            parents.put(row[0], List.of(row[1].split(",")));
        }
        for (String[] row : rows("verb-nouns.tsv")) {
            verbNouns.put(row[0], new LinkedHashSet<>(List.of(row[1].split(","))));
        }
        for (String[] row : rows("noun-forms.tsv")) {
            nounForms.put(row[0], new LinkedHashSet<>(List.of(row[1].split(","))));
        }
        try (InputStream in = Thesaurus.class.getResourceAsStream(BASE + "related-forms.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing related-forms.json");
            }
            Map<String, Map<String, List<String>>> hand =
                    new ObjectMapper().readValue(in, new TypeReference<>() {});
            hand.getOrDefault("verbs", Map.of()).forEach((k, v) -> handVerbs.put(k, new LinkedHashSet<>(v)));
            hand.getOrDefault("nouns", Map.of()).forEach((k, v) -> handNouns.put(k, new LinkedHashSet<>(v)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (InputStream in = Thesaurus.class.getResourceAsStream(BASE + "deny.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing deny.json");
            }
            Map<String, List<List<String>>> deny = new ObjectMapper().readValue(in, new TypeReference<>() {});
            for (List<String> pair : deny.getOrDefault("deny", List.of())) {
                denied.add(normalize(pair.get(0)) + "|" + normalize(pair.get(1)));
                denied.add(normalize(pair.get(1)) + "|" + normalize(pair.get(0)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Thesaurus builtIn() {
        Thesaurus local = instance;
        if (local == null) {
            synchronized (Thesaurus.class) {
                if (instance == null) {
                    instance = new Thesaurus();
                }
                local = instance;
            }
        }
        return local;
    }

    public Set<String> synonymsOf(String phrase) {
        String word = normalize(phrase);
        Set<String> out = new TreeSet<>();
        if (!synsetOf.containsKey(word) || instructionWords.contains(word)) {
            return out;
        }
        for (String other : lemmasOf.get(synsetOf.get(word))) {
            if (!other.equals(word) && !instructionWords.contains(other) && !isDenied(word, other)) {
                out.add(other);
            }
        }
        return out;
    }

    public boolean isKindOf(String narrower, String broader, int maxSteps) {
        String from = synsetOf.get(normalize(narrower));
        String to = synsetOf.get(normalize(broader));
        if (from == null || to == null || from.equals(to) || isDenied(normalize(narrower), normalize(broader))) {
            return false;
        }
        Set<String> frontier = Set.of(from);
        for (int step = 0; step < maxSteps; step++) {
            Set<String> next = new LinkedHashSet<>();
            for (String id : frontier) {
                next.addAll(parents.getOrDefault(id, List.of()));
            }
            if (next.contains(to)) {
                return true;
            }
            frontier = next;
        }
        return false;
    }

    public Set<String> relatedForms(String noun) {
        String word = normalize(noun);
        Set<String> out = new LinkedHashSet<>();
        for (String form : singularAndPlural(word)) {
            out.add(form);
            out.addAll(nounForms.getOrDefault(form, Set.of()));
            out.addAll(handNouns.getOrDefault(form, Set.of()));
            if (form.endsWith("ologist")) {
                out.add(form.substring(0, form.length() - "ist".length()) + "y");
            }
            if (form.endsWith("ician")) {
                out.add(form.substring(0, form.length() - "ician".length()) + "ics");
            }
        }
        return out;
    }

    public Set<String> nounsOfVerb(String verbForm) {
        String form = normalize(verbForm);
        if (instructionWords.contains(form) || COMMAND_VERBS.contains(form)) {
            return Set.of();
        }
        Set<String> out = new TreeSet<>(verbNouns.getOrDefault(form, Set.of()));
        out.addAll(handVerbs.getOrDefault(form, Set.of()));
        return out;
    }

    private boolean isDenied(String a, String b) {
        for (String x : List.of(a, SchemaLexicon.singular(a))) {
            for (String y : List.of(b, SchemaLexicon.singular(b))) {
                if (denied.contains(x + "|" + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> singularAndPlural(String word) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(word);
        if (word.endsWith("ies") && word.length() > 3) {
            forms.add(word.substring(0, word.length() - 3) + "y");
        } else if (word.endsWith("s") && !word.endsWith("ss")) {
            forms.add(word.substring(0, word.length() - 1));
        } else if (word.endsWith("y") && !word.matches(".*[aeiou]y")) {
            forms.add(word.substring(0, word.length() - 1) + "ies");
        } else if (word.endsWith("ss") || word.endsWith("x") || word.endsWith("ch") || word.endsWith("sh")) {
            forms.add(word + "es");
        } else {
            forms.add(word + "s");
        }
        return forms;
    }

    private static String normalize(String phrase) {
        return phrase.toLowerCase(Locale.ROOT).trim().replaceAll("\s+", " ");
    }

    private static List<String[]> rows(String file) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = Thesaurus.class.getResourceAsStream(BASE + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + file);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(line.split("\t"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
