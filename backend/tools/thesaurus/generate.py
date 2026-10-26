#!/usr/bin/env python3
"""One-off generator for the rule engine's WordNet thesaurus resources (needs nltk, wordnet, omw-1.4)."""
import argparse
import json
import os
import re
import sys
from collections import defaultdict

from nltk.corpus import wordnet as wn

ALLOWED = {
    "noun.person", "noun.group", "noun.artifact", "noun.location", "noun.act", "noun.event",
    "noun.possession", "noun.communication", "noun.time", "noun.quantity", "noun.attribute",
}
THRESHOLDS = [0.5, 0.6, 0.7, 0.8]
MIN_SHARE = THRESHOLDS[0]
WORD = re.compile(r"^[a-z]+(?:[ -][a-z]+){0,2}$")
COMMAND_VERBS = {"show", "list", "give", "find", "get", "display", "sort", "order", "group", "count", "total",
                 "top", "limit", "delete", "remove", "select", "return", "fetch", "tell", "sum", "average"}

GUARDS = [("class", "course"), ("module", "faculty"), ("faculty", "ability"), ("section", "department"),
          ("order", "command")]
SYNONYM_PAIRS = [("pupil", "student"), ("given name", "first name"), ("teacher", "instructor"),
                 ("doctor", "physician")] + GUARDS
KIND_PAIRS = [("surgeon", "physician"), ("cardiologist", "doctor")]
VERB_PAIRS = [("admitted", "admission"), ("enrolled", "enrollment"), ("born", "birth"), ("surgeon", "surgery")]


def sid(synset):
    return "%08d" % synset.offset()


def key(name):
    return name.lower().replace("_", " ")


def lemma_count(synset, name):
    for lemma in synset.lemmas():
        if key(lemma.name()) == name:
            return lemma.count()
    return 0


def first_sense(name):
    """Returns (synset, share) for the first allowed noun sense of a lemma, or None."""
    senses = wn.synsets(name.replace(" ", "_"), "n")
    counts = [lemma_count(s, name) for s in senses]
    total = sum(counts)
    for synset, count in zip(senses, counts):
        if synset.lexname() in ALLOWED:
            if total == 0:
                share = 1.0 if len(senses) == 1 else 0.0
            else:
                share = count / total
            return synset, share
    return None


def parents_of(synset):
    return sorted(set(synset.hypernyms()), key=lambda s: s.offset())


def build_senses():
    names = set()
    for synset in wn.all_synsets("n"):
        for lemma in synset.lemmas():
            n = key(lemma.name())
            if WORD.match(n):
                names.add(n)
    rows = {}
    for n in names:
        found = first_sense(n)
        if found and found[1] >= MIN_SHARE:
            rows[n] = found
    return rows


def build_parents(senses):
    parents = {}
    for synset, _ in sorted(senses.values(), key=lambda v: v[0].offset()):
        ps = parents_of(synset)
        parents[synset.offset()] = ps
        for p in ps:
            parents[p.offset()] = parents_of(p)
    return parents


def verb_forms(base):
    forms = {base}
    if " " in base or "-" in base:
        return set()
    if re.search(r"(s|x|z|ch|sh)$", base):
        forms.add(base + "es")
    elif re.search(r"[^aeiou]y$", base):
        forms.add(base[:-1] + "ies")
    else:
        forms.add(base + "s")
    if base.endswith("e") and not base.endswith("ee"):
        forms.update({base + "d", base[:-1] + "ing"})
    elif re.search(r"[^aeiou]y$", base):
        forms.update({base[:-1] + "ied", base + "ing"})
    else:
        forms.update({base + "ed", base + "ing"})
        if re.search(r"[^aeiou][aeiou][^aeiouwxy]$", base):
            forms.update({base + base[-1] + "ed", base + base[-1] + "ing"})
    return forms


def irregular_forms():
    out = defaultdict(set)
    wn.ensure_loaded()
    for form, bases in wn._exception_map["v"].items():
        for b in bases:
            out[b].add(form)
    return out


def related_nouns(lemma):
    result = set()
    for rel in lemma.derivationally_related_forms():
        if rel.synset().pos() == "n" and rel.synset().lexname() in ALLOWED:
            result.add(key(rel.name()))
    return result


def build_verb_derivations():
    irregular = irregular_forms()
    by_form = defaultdict(dict)
    verbs = {key(lm.name()) for s in wn.all_synsets("v") for lm in s.lemmas()}
    for verb in verbs:
        if not re.match(r"^[a-z]+$", verb) or verb in COMMAND_VERBS:
            continue
        synsets = wn.synsets(verb, "v")
        if not synsets:
            continue
        nouns = set()
        for lemma in synsets[0].lemmas():
            if key(lemma.name()) == verb:
                nouns = related_nouns(lemma)
        if not nouns:
            continue
        for form in verb_forms(verb) | irregular.get(verb, set()):
            by_form[form][verb] = (synsets[0].name(), nouns)
    out = {}
    for form, per_verb in by_form.items():
        if len({synset for synset, _ in per_verb.values()}) == 1:
            out[form] = set().union(*(nouns for _, nouns in per_verb.values()))
    return out


def build_noun_derivations(senses):
    out = {}
    for name, (synset, _) in senses.items():
        if " " in name or "-" in name:
            continue
        related = set()
        for lemma in synset.lemmas():
            if key(lemma.name()) != name:
                continue
            for rel in lemma.derivationally_related_forms():
                target = rel.synset()
                n = key(rel.name())
                if n == name or not WORD.match(n):
                    continue
                if target.pos() == "n" and target.lexname() in ALLOWED:
                    related.add(n)
                elif target.pos() in ("a", "s"):
                    related.add(n)
        if related:
            out[name] = related
    return out


def write_tsv(path, lines):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for line in sorted(lines):
            f.write(line + "\n")


def describe(synset):
    return "%s (%s): %s" % (synset.name(), synset.lexname(), synset.definition())


def hypernym_path(narrow, broad, steps=2):
    frontier = [(narrow, [narrow])]
    for _ in range(steps):
        nxt = []
        for s, path in frontier:
            for p in parents_of(s):
                if p == broad:
                    return path + [p]
                nxt.append((p, path + [p]))
        frontier = nxt
    return None


def linked(a, b, t):
    fa, fb = first_sense(a), first_sense(b)
    return bool(fa and fb and fa[0] == fb[0] and fa[1] >= t and fb[1] >= t)


def sense_lines(pairs_names):
    out = []
    for n in pairs_names:
        f = first_sense(n)
        if f:
            out.append("- `%s` resolves to %s ; share %.2f" % (n, describe(f[0]), f[1]))
        else:
            out.append("- `%s` has no database-like noun sense" % n)
    return out


def write_review(path, threshold, verb_derivs, noun_derivs, hand):
    out = ["# Thesaurus review", "",
           "Generated by `generate.py --review`. Chosen DOMINANT_SHARE = **%s**." % threshold, "",
           "## Guard pairs by threshold (LINKED means the guard would fail)", "",
           "| pair | 0.5 | 0.6 | 0.7 | 0.8 |", "|---|---|---|---|---|"]
    for a, b in GUARDS:
        out.append("| %s / %s | %s |" % (a, b, " | ".join("LINKED" if linked(a, b, t) else "unlinked" for t in THRESHOLDS)))
    t = threshold if isinstance(threshold, float) else 1.1
    out += ["", "## Synonym pairs", ""]
    for a, b in SYNONYM_PAIRS:
        out.append("### %s / %s%s" % (a, b, "  (guard)" if (a, b) in GUARDS else ""))
        out += sense_lines([a, b])
        out += ["- **%s**" % ("LINKED" if linked(a, b, t) else "NOT LINKED"), ""]
    out += ["## Kind-of pairs", ""]
    for a, b in KIND_PAIRS:
        out.append("### %s kind-of %s" % (a, b))
        out += sense_lines([a, b])
        fa, fb = first_sense(a), first_sense(b)
        ok = False
        if fa and fb:
            p = hypernym_path(fa[0], fb[0])
            out.append("- hypernym path (<=2 steps): %s" % (" -> ".join(s.name() for s in p) if p else "none"))
            ok = bool(p) and fa[1] >= t and fb[1] >= t
        out += ["- **%s**" % ("LINKED" if ok else "NOT LINKED"), ""]
    out += ["## Verb / derivation pairs", ""]
    for form, noun in VERB_PAIRS:
        out.append("### %s -> %s" % (form, noun))
        table = noun_derivs.get(form) if form == "surgeon" else verb_derivs.get(form)
        out.append("- generated entry for `%s`: %s" % (form, ",".join(sorted(table)) if table else "none"))
        written = hand.get("nouns" if form == "surgeon" else "verbs", {}).get(form, [])
        out.append("- related-forms.json entry for `%s`: %s" % (form, ",".join(sorted(written)) if written else "none"))
        table = set(table or ()) | set(written)
        stems = [form] + list(wn._morphy(form, "v") or [])
        for stem in dict.fromkeys(stems):
            vs = wn.synsets(stem, "v")
            if vs:
                s = vs[0]
                rel = sorted({"%s[%s]" % (key(r.name()), r.synset().lexname()) for lm in s.lemmas()
                              if key(lm.name()) == stem for r in lm.derivationally_related_forms()})
                out.append("- `%s` first verb sense %s ; related: %s" % (stem, describe(s), ", ".join(rel) or "none"))
        out += ["- **%s**" % ("LINKED" if table and noun in table else "NOT LINKED"), ""]
    deny = hand.get("deny", [])
    out += ["## Denied pairs (deny.json, applied both ways at runtime)", ""]
    out += ["- %s / %s" % (a, b) for a, b in deny] + [""]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")


def choose_threshold():
    for t in THRESHOLDS:
        if not any(linked(a, b, t) for a, b in GUARDS):
            return t
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--review", action="store_true", help="also write review.md next to this script")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    senses = build_senses()
    parents = build_parents(senses)
    verbs = build_verb_derivations()
    nouns = build_noun_derivations(senses)

    write_tsv(os.path.join(args.out_dir, "senses.tsv"),
              ["%s\t%s\t%.2f" % (n, sid(s), sh) for n, (s, sh) in senses.items()])
    write_tsv(os.path.join(args.out_dir, "parents.tsv"),
              ["%08d\t%s" % (o, ",".join("%08d" % p.offset() for p in ps)) for o, ps in parents.items() if ps])
    write_tsv(os.path.join(args.out_dir, "verb-nouns.tsv"),
              ["%s	%s" % (k, ",".join(sorted(v))) for k, v in verbs.items() if v])
    write_tsv(os.path.join(args.out_dir, "noun-forms.tsv"),
              ["%s	%s" % (k, ",".join(sorted(v))) for k, v in nouns.items() if v])
    with open(os.path.join(args.out_dir, "WORDNET-LICENSE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("WordNet %s, via NLTK\n\n%s\n" % (wn.get_version(), wn.license()))

    chosen = choose_threshold()
    print("DOMINANT_SHARE chosen:", chosen)
    if args.review:
        here = os.path.dirname(os.path.abspath(__file__))
        hand = {}
        for name in ("related-forms.json", "deny.json"):
            file = os.path.join(args.out_dir, name)
            if os.path.exists(file):
                with open(file, encoding="utf-8") as f:
                    hand.update(json.load(f))
        write_review(os.path.join(here, "review.md"),
                     chosen if chosen is not None else "none keeps all guards unlinked", verbs, nouns, hand)


if __name__ == "__main__":
    sys.exit(main())
