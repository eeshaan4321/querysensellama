#!/usr/bin/env python3
"""Writes schema-links.md: every THESAURUS link and NARROWER kind-of candidate the shipped resources give the four acceptance schemas."""
import json
import os
import re
import sys
from collections import defaultdict

DOMINANT_SHARE = 0.5
MAX_STEPS = 2
NAMING_WORDS = {"name", "surname", "title", "email", "address", "phone", "code", "id"}

SCHEMAS = [
    ("shop demo (KnownSchema)", [
        ("categories", ["id", "name"]),
        ("customers", ["id", "name", "email", "city", "phone"]),
        ("departments", ["id", "name"]),
        ("employees", ["id", "name", "email", "department_id", "manager_id", "hire_date"]),
        ("order_items", ["id", "order_id", "product_id", "quantity", "unit_price"]),
        ("orders", ["id", "customer_id", "employee_id", "order_date", "status"]),
        ("payments", ["id", "order_id", "amount", "payment_date", "method"]),
        ("products", ["id", "name", "price", "category_id", "supplier_id"]),
        ("suppliers", ["id", "name", "contact_email"]),
    ], {}),
    ("campus (RewordingAcceptanceTest)", [
        ("departments", ["dept_id", "code", "name", "parent_dept_id", "head_faculty_id"]),
        ("faculty", ["faculty_id", "full_name", "email", "dept_id"]),
        ("programs", ["program_id", "name", "dept_id"]),
        ("students", ["student_id", "full_name", "program_id", "advisor_id", "admission_year", "level"]),
        ("offers", ["offer_id", "student_id", "ctc_lpa", "offered_on"]),
    ], {"students.level": ["Undergraduate", "Graduate", "Doctoral"]}),
    ("company (RewordingAcceptanceTest)", [
        ("EMPLOYEE", ["Fname", "Minit", "Lname", "Ssn", "Bdate", "Sex", "Salary", "Super_ssn", "Dno"]),
        ("DEPARTMENT", ["Dname", "Dnumber", "Mgr_ssn"]),
    ], {}),
    ("hospital (RewordingAcceptanceTest)", [
        ("physicians", ["physician_id", "full_name", "department"]),
        ("patients", ["patient_id", "first_name", "last_name", "physician_id"]),
        ("wards", ["ward_id", "name"]),
        ("admissions", ["admission_id", "patient_id", "ward_id", "admission_date", "discharge_date"]),
    ], {"physicians.department": ["Surgery", "Cardiology", "Pediatrics"]}),
]


def words(name):
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name)
    spaced = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", spaced)
    spaced = re.sub(r"([A-Za-z])([0-9])", r"\1 \2", spaced)
    spaced = re.sub(r"([0-9])([A-Za-z])", r"\1 \2", spaced)
    return [w.lower() for w in re.split(r"[^A-Za-z0-9]+", spaced) if w]


def singular(word):
    if word.endswith("ies") and len(word) > 3:
        return word[:-3] + "y"
    if re.search(r"(ses|xes|zes|ches|shes)$", word):
        return word[:-2]
    if word.endswith("s") and not word.endswith("ss") and len(word) > 1:
        return word[:-1]
    return word


class Resources:
    def __init__(self, folder):
        self.synset_of, self.lemmas_of, self.parents = {}, defaultdict(list), {}
        self.noun_forms, self.denied = {}, set()
        for row in self.rows(folder, "senses.tsv"):
            if float(row[2]) >= DOMINANT_SHARE:
                self.synset_of[row[0]] = row[1]
                self.lemmas_of[row[1]].append(row[0])
        for row in self.rows(folder, "parents.tsv"):
            self.parents[row[0]] = row[1].split(",")
        for row in self.rows(folder, "noun-forms.tsv"):
            self.noun_forms[row[0]] = row[1].split(",")
        with open(os.path.join(folder, "related-forms.json"), encoding="utf-8") as f:
            self.hand_nouns = json.load(f).get("nouns", {})
        with open(os.path.join(folder, "deny.json"), encoding="utf-8") as f:
            for a, b in json.load(f).get("deny", []):
                self.denied.update({(a, b), (b, a)})
        self.children = defaultdict(set)
        for child, ps in self.parents.items():
            for p in ps:
                self.children[p].add(child)

    @staticmethod
    def rows(folder, name):
        with open(os.path.join(folder, name), encoding="utf-8") as f:
            return [line.rstrip("\n").split("\t") for line in f if line.strip()]

    def is_denied(self, a, b):
        return any((x, y) in self.denied for x in {a, singular(a)} for y in {b, singular(b)})

    def synonyms(self, phrase):
        sid = self.synset_of.get(phrase)
        if sid is None:
            return set()
        return {o for o in self.lemmas_of[sid] if o != phrase and not self.is_denied(phrase, o)}

    def kinds_of(self, broad):
        target = self.synset_of.get(broad)
        if target is None:
            return {}
        out = {}
        frontier = {target}
        for step in range(1, MAX_STEPS + 1):
            nxt = set()
            for sid in frontier:
                nxt |= self.children.get(sid, set())
            for sid in nxt:
                for lemma in self.lemmas_of.get(sid, []):
                    if re.fullmatch(r"[a-z]+", lemma) and sid != target and not self.is_denied(lemma, broad):
                        out.setdefault(lemma, step)
            frontier = nxt
        return out

    def related_forms(self, noun):
        forms = {noun, noun + "s", singular(noun)}
        for form in list(forms):
            forms.update(self.noun_forms.get(form, []))
            forms.update(self.hand_nouns.get(form, []))
            if form.endswith("ologist"):
                forms.add(form[:-3] + "y")
            if form.endswith("ician"):
                forms.add(form[:-5] + "ics")
        return forms


ABBREVIATIONS = {}


def expand(name_words):
    out = []
    for w in name_words:
        out += ABBREVIATIONS.get(w, w).split(" ")
    return out


def column_links(res, column):
    base = " ".join(words(column))
    related = res.synonyms(base) | res.synonyms(singular(base))
    if " " in base:
        for w in base.split(" "):
            related |= res.synonyms(w) | res.synonyms(singular(w))
    return sorted(related)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        here, "..", "..", "src", "main", "resources", "querybuilder", "thesaurus")
    res = Resources(folder)
    with open(os.path.join(folder, "..", "abbreviations.json"), encoding="utf-8") as f:
        ABBREVIATIONS.update(json.load(f))
    out = ["# Schema links for review", "",
           "Generated by `schema_links.py` from the shipped resources (deny.json applied). For human review: "
           "each link below is one the rule engine would accept. Instruction words (show, list, order, ...) are "
           "filtered at runtime and may still appear here. A NARROWER candidate only builds when exactly one "
           "eligible column holds exactly one stored value among its related forms; columns whose name words "
           "include (after expanding abbreviations such as Lname) " + ", ".join(sorted(NAMING_WORDS)) + " are never eligible, nor is any column whose distinct "
           "share is above 0.5.", ""]
    for title, tables, values in SCHEMAS:
        out += ["## " + title, ""]
        for table, columns in tables:
            name_words = words(table)
            phrase = " ".join(name_words)
            links = set()
            if " " not in phrase:
                links = res.synonyms(phrase) | res.synonyms(singular(phrase))
            out.append("### %s" % table)
            out.append("- table THESAURUS: %s" % (", ".join(sorted(links)) or "none"))
            for column in columns:
                out.append("- column `%s` THESAURUS: %s" % (column, ", ".join(column_links(res, column)) or "none"))
            head = singular(name_words[-1]) if name_words else ""
            kinds = res.kinds_of(head)
            eligible = [c for c in columns if not set(expand(words(c))) & NAMING_WORDS]
            out.append("- NARROWER head `%s`, eligible columns: %s" % (head, ", ".join(eligible) or "none"))
            if kinds:
                listed = ", ".join("%s(%d)" % (w, s) for w, s in sorted(kinds.items()))
                out.append("- NARROWER kind-of candidates (word(steps)), %d: %s" % (len(kinds), listed))
            else:
                out.append("- NARROWER kind-of candidates: none")
            for column in eligible:
                stored = values.get("%s.%s" % (table, column))
                if not stored:
                    continue
                hits = []
                for word in sorted(kinds):
                    forms = res.related_forms(word)
                    matched = [v for v in stored if v.lower() in forms]
                    if len(matched) == 1:
                        hits.append("%s -> %s" % (word, matched[0]))
                out.append("- with the test values of `%s` (%s), these candidates would narrow: %s"
                           % (column, ", ".join(stored), "; ".join(hits) or "none"))
            out.append("")
    with open(os.path.join(here, "schema-links.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    sys.exit(main())
