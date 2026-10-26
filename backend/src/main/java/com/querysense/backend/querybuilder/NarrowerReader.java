package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a word naming a kind of what a table holds as that table filtered to the one stored value naming the kind. */
@Component
public class NarrowerReader {

    static final int MAX_STEPS = 2;
    static final double MAX_DISTINCT_SHARE = 0.5;

    private static final Set<String> NAMING_WORDS =
            Set.of("name", "surname", "title", "email", "address", "phone", "code", "id");

    private static final Pattern WORD = Pattern.compile("[a-z]+");

    record Narrowing(String word, KnownSchema.Table table, String column, String stored) {}

    private final Thesaurus thesaurus;

    public NarrowerReader() {
        this(Thesaurus.builtIn());
    }

    NarrowerReader(Thesaurus thesaurus) {
        this.thesaurus = thesaurus;
    }

    Optional<Narrowing> read(String text, List<KnownSchema.Table> all, SchemaMetadata metadata, Set<String> readWords) {
        List<Narrowing> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher word = WORD.matcher(text);
        while (word.find()) {
            String w = word.group();
            if (readWords.contains(w) || !seen.add(w)) {
                continue;
            }
            narrowing(w, all, metadata).ifPresent(found::add);
        }
        if (found.size() > 1) {
            throw new DeclineException("\"" + found.get(0).word() + "\" and \"" + found.get(1).word() + "\" each read "
                    + "as a kind of " + found.get(0).table().name() + (found.get(0).table().equals(found.get(1).table())
                    ? "" : " and " + found.get(1).table().name()) + ", and this engine reads only one such word per "
                    + "question.");
        }
        return found.stream().findFirst();
    }

    private Optional<Narrowing> narrowing(String word, List<KnownSchema.Table> all, SchemaMetadata metadata) {
        String singular = SchemaLexicon.singular(word);
        Set<String> forms = thesaurus.relatedForms(singular);
        List<Narrowing> pairs = new ArrayList<>();
        for (KnownSchema.Table table : all) {
            List<String> nameWords = NameWords.words(table.name());
            if (nameWords.isEmpty()) {
                continue;
            }
            String head = SchemaLexicon.singular(nameWords.get(nameWords.size() - 1));
            if (!thesaurus.isKindOf(singular, head, MAX_STEPS)) {
                continue;
            }
            for (String column : table.columns()) {
                if (Abbreviations.builtIn().expand(NameWords.words(column)).stream().anyMatch(NAMING_WORDS::contains)) {
                    continue;
                }
                List<String> stored = metadata.getDistinctValues(table.name(), column).stream()
                        .filter(v -> v != null && forms.contains(v.trim().toLowerCase(Locale.ROOT)))
                        .distinct()
                        .toList();
                if (stored.isEmpty()) {
                    continue;
                }
                OptionalDouble share = metadata.distinctShare(table.name(), column);
                if (share.isPresent() && share.getAsDouble() > MAX_DISTINCT_SHARE) {
                    continue;
                }
                if (stored.size() > 1) {
                    return Optional.empty();
                }
                if (stored.size() == 1) {
                    pairs.add(new Narrowing(word, table, column, stored.get(0)));
                }
            }
        }
        return pairs.size() == 1 ? Optional.of(pairs.get(0)) : Optional.empty();
    }
}
