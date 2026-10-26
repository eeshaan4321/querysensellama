# Thesaurus generator

One-off dev tool; the backend never runs Python. It writes the WordNet slice the rule engine loads.

    python -m venv venv && venv/bin/pip install nltk
    python -m nltk.downloader wordnet omw-1.4
    python backend/tools/thesaurus/generate.py backend/src/main/resources/querybuilder/thesaurus --review

Outputs in `backend/src/main/resources/querybuilder/thesaurus/`: `senses.tsv`, `parents.tsv` (class hypernyms only; instance links such as paris -> city are left out), `verb-nouns.tsv` (verb form to nouns), `noun-forms.tsv` (noun to related nouns/adjectives), `WORDNET-LICENSE.txt`. Output is deterministic. `--review` also rewrites `review.md` here, reading the hand-written `related-forms.json` and `deny.json` from the output folder.

    python backend/tools/thesaurus/schema_links.py

writes `schema-links.md` here from the shipped resources (no NLTK needed): every THESAURUS link and NARROWER kind-of candidate the four acceptance schemas would accept, for a person to review. Loose pairs found there go into `deny.json`.

## Chosen DOMINANT_SHARE = 0.5

The lowest of {0.5, 0.6, 0.7, 0.8} keeping every guard pair unlinked. Class/course, module/faculty, faculty/ability, section/department and order/command are unlinked at all four values. The order/command margin is thin: order 0.46, command 0.53.

The full review table is in `review.md`.

The 0.5 threshold must not be lowered: order and command sit at 0.46 and 0.53, so anything below 0.5 risks linking them.

`related-forms.json` is hand-written, not generated: `born` -> `birth` and `surgeon` -> `surgery, surgical` are not in WordNet.

`deny.json` is hand-written: pairs that never link as synonyms or kinds-of, applied both ways round.
