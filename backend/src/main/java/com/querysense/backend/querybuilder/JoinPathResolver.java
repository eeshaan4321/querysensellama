package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Finds the declared foreign keys that join two tables, or chain up to four, using the question's wording to pick among equals. */
@Component
public class JoinPathResolver {

    public sealed interface JoinResolution permits Resolved, None, Ambiguous {}

    public record Resolved(KnownSchema.ForeignKey edge, List<Choice> choices) implements JoinResolution {
        public Resolved(KnownSchema.ForeignKey edge) {
            this(edge, List.of());
        }
    }

    public record None() implements JoinResolution {}

    public record Ambiguous(List<KnownSchema.ForeignKey> edges) implements JoinResolution {}

    public record Choice(String phrase, String item) {}

    private static final Set<String> KEY_WORDS = Set.of("id", "key", "fk", "ref", "code", "number");

    private final ActiveSchemaProvider activeSchema;

    public JoinPathResolver(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public JoinResolution resolve(String tableA, String tableB, Dialect dialect) {
        return resolve(tableA, tableB, dialect, Set.of());
    }

    public JoinResolution resolve(String tableA, String tableB, Dialect dialect, Set<String> questionWords) {
        if (tableA == null || tableB == null) {
            return new None();
        }

        List<KnownSchema.ForeignKey> edges = activeSchema.foreignKeys(dialect).stream()
                .filter(fk -> !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .filter(fk -> relates(fk, tableA, tableB))
                .toList();

        if (edges.isEmpty()) {
            return new None();
        }
        if (edges.size() > 1) {
            Optional<List<KnownSchema.ForeignKey>> chosen = byWording(edges.stream().map(List::of).toList(), questionWords);
            return chosen.<JoinResolution>map(path -> new Resolved(path.get(0), choices(tableB, path)))
                    .orElseGet(() -> new Ambiguous(edges));
        }
        return new Resolved(edges.get(0));
    }

    public List<KnownSchema.ForeignKey> keysFrom(String table, Dialect dialect) {
        return activeSchema.foreignKeys(dialect).stream()
                .filter(fk -> fk.fromTable().equalsIgnoreCase(table) && !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .toList();
    }

    static List<String> role(KnownSchema.ForeignKey fk) {
        Abbreviations abbreviations = Abbreviations.builtIn();
        List<String> target = new ArrayList<>(abbreviations.expand(NameWords.words(fk.toColumn())));
        abbreviations.expand(NameWords.words(fk.toTable())).forEach(w -> target.addAll(SchemaLexicon.forms(w)));
        return abbreviations.expand(NameWords.words(fk.fromColumn())).stream()
                .filter(w -> !KEY_WORDS.contains(w) && !target.contains(w))
                .filter(w -> w.length() < 3 || target.stream().noneMatch(t -> t.startsWith(w)))
                .toList();
    }

    private static Optional<List<KnownSchema.ForeignKey>> byWording(List<List<KnownSchema.ForeignKey>> paths,
                                                                    Set<String> questionWords) {
        int best = -1;
        List<List<KnownSchema.ForeignKey>> top = new ArrayList<>();
        for (List<KnownSchema.ForeignKey> path : paths) {
            int named = 0;
            boolean admissible = true;
            for (KnownSchema.ForeignKey edge : path) {
                List<String> role = role(edge);
                if (role.isEmpty()) {
                    continue;
                }
                if (questionWords.containsAll(role)) {
                    named++;
                } else {
                    admissible = false;
                }
            }
            if (!admissible) {
                continue;
            }
            if (named > best) {
                best = named;
                top.clear();
            }
            if (named == best) {
                top.add(path);
            }
        }
        return top.size() == 1 ? Optional.of(top.get(0)) : Optional.empty();
    }

    private static List<Choice> choices(String target, List<KnownSchema.ForeignKey> path) {
        List<Choice> out = new ArrayList<>();
        for (KnownSchema.ForeignKey edge : path) {
            List<String> role = role(edge);
            if (!role.isEmpty()) {
                out.add(new Choice(String.join(" ", role), edge.fromTable() + "." + edge.fromColumn()));
            }
        }
        if (out.isEmpty()) {
            out.add(new Choice(NameWords.phrase(target), path.stream()
                    .map(e -> e.fromTable() + "." + e.fromColumn())
                    .collect(Collectors.joining(", "))));
        }
        return List.copyOf(out);
    }

    private boolean relates(KnownSchema.ForeignKey fk, String a, String b) {
        return (fk.fromTable().equalsIgnoreCase(a) && fk.toTable().equalsIgnoreCase(b))
                || (fk.fromTable().equalsIgnoreCase(b) && fk.toTable().equalsIgnoreCase(a));
    }

    public static final int MAX_TABLES = 4;
    private static final int MAX_EDGES = MAX_TABLES - 1;

    public sealed interface ChainResolution permits Chain, NoChain, AmbiguousChain, TooFar {}

    public record Chain(List<KnownSchema.ForeignKey> edges, List<Choice> choices) implements ChainResolution {
        public Chain(List<KnownSchema.ForeignKey> edges) {
            this(edges, List.of());
        }
    }

    public record NoChain(String table) implements ChainResolution {}

    public record AmbiguousChain(String table, List<List<KnownSchema.ForeignKey>> paths) implements ChainResolution {}

    public record TooFar(int tables) implements ChainResolution {}

    public ChainResolution resolveChain(String subject, List<String> others, Dialect dialect) {
        return resolveChain(subject, others, dialect, Set.of());
    }

    public ChainResolution resolveChain(String subject, List<String> others, Dialect dialect, Set<String> questionWords) {
        List<KnownSchema.ForeignKey> graph = activeSchema.foreignKeys(dialect).stream()
                .filter(fk -> !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .toList();
        List<KnownSchema.ForeignKey> union = new ArrayList<>();
        List<Choice> choices = new ArrayList<>();
        for (String target : others) {
            List<List<KnownSchema.ForeignKey>> shortest = shortestPaths(subject, target, graph);
            if (shortest.isEmpty()) {
                return new NoChain(target);
            }
            if (shortest.size() > 1) {
                Optional<List<KnownSchema.ForeignKey>> chosen = byWording(shortest, questionWords);
                if (chosen.isEmpty()) {
                    return new AmbiguousChain(target, shortest);
                }
                choices.addAll(choices(target, chosen.get()));
                shortest = List.of(chosen.get());
            }
            shortest.get(0).stream().filter(e -> !union.contains(e)).forEach(union::add);
        }
        Set<String> tables = new LinkedHashSet<>();
        tables.add(subject.toLowerCase(Locale.ROOT));
        union.forEach(e -> {
            tables.add(e.fromTable().toLowerCase(Locale.ROOT));
            tables.add(e.toTable().toLowerCase(Locale.ROOT));
        });
        if (tables.size() > MAX_TABLES) {
            return new TooFar(tables.size());
        }
        return new Chain(outward(subject, union), List.copyOf(choices));
    }

    private record Walk(String at, List<KnownSchema.ForeignKey> path) {}

    private static List<List<KnownSchema.ForeignKey>> shortestPaths(String from, String to,
                                                                    List<KnownSchema.ForeignKey> graph) {
        List<Walk> frontier = List.of(new Walk(from, List.of()));
        for (int depth = 1; depth <= MAX_EDGES; depth++) {
            List<Walk> next = new ArrayList<>();
            List<List<KnownSchema.ForeignKey>> found = new ArrayList<>();
            for (Walk walk : frontier) {
                for (KnownSchema.ForeignKey edge : graph) {
                    String other = edge.fromTable().equalsIgnoreCase(walk.at()) ? edge.toTable()
                            : edge.toTable().equalsIgnoreCase(walk.at()) ? edge.fromTable() : null;
                    if (other == null || visits(walk, other, from)) {
                        continue;
                    }
                    List<KnownSchema.ForeignKey> path = new ArrayList<>(walk.path());
                    path.add(edge);
                    if (other.equalsIgnoreCase(to)) {
                        found.add(List.copyOf(path));
                    } else {
                        next.add(new Walk(other, path));
                    }
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
            frontier = next;
        }
        return List.of();
    }

    private static boolean visits(Walk walk, String table, String start) {
        if (table.equalsIgnoreCase(start)) {
            return true;
        }
        return walk.path().stream().anyMatch(e -> e.fromTable().equalsIgnoreCase(table) || e.toTable().equalsIgnoreCase(table));
    }

    private static List<KnownSchema.ForeignKey> outward(String subject, List<KnownSchema.ForeignKey> union) {
        Set<String> known = new HashSet<>();
        known.add(subject.toLowerCase(Locale.ROOT));
        List<KnownSchema.ForeignKey> remaining = new ArrayList<>(union);
        List<KnownSchema.ForeignKey> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            KnownSchema.ForeignKey next = remaining.stream()
                    .filter(e -> known.contains(e.fromTable().toLowerCase(Locale.ROOT))
                            || known.contains(e.toTable().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElseThrow();
            remaining.remove(next);
            ordered.add(next);
            known.add(next.fromTable().toLowerCase(Locale.ROOT));
            known.add(next.toTable().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(ordered);
    }
}
