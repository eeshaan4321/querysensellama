package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.KnownSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Reads the longest run of question words that names exactly one column. */
final class ColumnPhrase {

    record Reading(KnownSchema.Table table, String column, String phrase, int start, int end,
                   SchemaLexicon.MatchKind kind) {}

    private record Word(String text, int start, int end) {}

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    private final SchemaBinder binder;

    ColumnPhrase(SchemaBinder binder) {
        this.binder = binder;
    }

    Optional<Reading> after(String text, int from, List<KnownSchema.Table> tables) {
        List<Word> words = words(text).stream().filter(w -> w.start() >= from).toList();
        for (int n = Math.min(words.size(), binder.longestColumnPhrase(tables)); n >= 1; n--) {
            Optional<Reading> found = read(words.subList(0, n), tables);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    Optional<Reading> before(String text, int to, List<KnownSchema.Table> tables) {
        List<Word> words = words(text).stream().filter(w -> w.end() <= to).toList();
        for (int n = Math.min(words.size(), binder.longestColumnPhrase(tables)); n >= 1; n--) {
            Optional<Reading> found = read(words.subList(words.size() - n, words.size()), tables);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    Optional<Reading> beforeExact(String text, int to, KnownSchema.Table table) {
        List<Word> words = words(text).stream().filter(w -> w.end() <= to).toList();
        for (int n = Math.min(words.size(), binder.longestColumnPhrase(List.of(table))); n >= 1; n--) {
            List<Word> run = words.subList(words.size() - n, words.size());
            String phrase = join(run);
            Optional<String> column = binder.bindColumn(table, phrase);
            if (column.isPresent()) {
                return Optional.of(new Reading(table, column.get(), phrase, run.get(0).start(),
                        run.get(run.size() - 1).end(), SchemaLexicon.MatchKind.EXACT));
            }
        }
        return Optional.empty();
    }

    private Optional<Reading> read(List<Word> run, List<KnownSchema.Table> tables) {
        String phrase = join(run);
        int start = run.get(0).start();
        int end = run.get(run.size() - 1).end();
        KnownSchema.Table subject = tables.get(0);
        SchemaLexicon.ColumnMatch own = binder.columnMatch(subject, phrase);
        if (own instanceof SchemaLexicon.Ambiguous ambiguous) {
            throw new AmbiguousWordException(QueryIntentParser.ambiguityMessage(ambiguous.ambiguity().phrase(),
                    ambiguous.ambiguity().candidates().stream().map(c -> subject.name() + "." + c).toList()));
        }
        if (own instanceof SchemaLexicon.Bound bound) {
            return Optional.of(new Reading(subject, bound.binding().item(), phrase, start, end, bound.binding().kind()));
        }
        List<Reading> found = new ArrayList<>();
        for (KnownSchema.Table table : tables.subList(1, tables.size())) {
            if (binder.columnMatch(table, phrase) instanceof SchemaLexicon.Bound bound) {
                found.add(new Reading(table, bound.binding().item(), phrase, start, end, bound.binding().kind()));
            }
        }
        if (found.size() > 1) {
            throw new AmbiguousWordException(QueryIntentParser.ambiguityMessage(phrase,
                    found.stream().map(r -> r.table().name() + "." + r.column()).toList()));
        }
        return found.stream().findFirst();
    }

    private static List<Word> words(String text) {
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new Word(m.group(), m.start(), m.end()));
        }
        return words;
    }

    private static String join(List<Word> run) {
        return run.stream().map(Word::text).collect(Collectors.joining(" "));
    }
}
