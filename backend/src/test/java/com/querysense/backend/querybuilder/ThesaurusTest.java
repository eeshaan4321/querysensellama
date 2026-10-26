package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThesaurusTest {

    private final Thesaurus thesaurus = Thesaurus.builtIn();

    @Test
    @DisplayName("words sharing a dominant first meaning are synonyms")
    void synonyms() {
        assertThat(thesaurus.synonymsOf("pupil")).contains("student");
        assertThat(thesaurus.synonymsOf("given name")).contains("first name");
        assertThat(thesaurus.synonymsOf("teacher")).contains("instructor");
        assertThat(thesaurus.synonymsOf("doctor")).contains("physician");
    }

    @Test
    @DisplayName("loose pairs stay unlinked")
    void guards() {
        assertThat(thesaurus.synonymsOf("class")).doesNotContain("course");
        assertThat(thesaurus.synonymsOf("module")).doesNotContain("faculty");
        assertThat(thesaurus.synonymsOf("faculty")).doesNotContain("ability");
        assertThat(thesaurus.synonymsOf("section")).doesNotContain("department");
        assertThat(thesaurus.synonymsOf("command")).doesNotContain("order");
        assertThat(thesaurus.synonymsOf("order")).doesNotContain("command");
    }

    @Test
    @DisplayName("a narrower word is a kind of the broader one, never the reverse")
    void kindOf() {
        assertThat(thesaurus.isKindOf("surgeon", "physician", 2)).isTrue();
        assertThat(thesaurus.isKindOf("cardiologist", "doctor", 2)).isTrue();
        assertThat(thesaurus.isKindOf("physician", "surgeon", 2)).isFalse();
    }

    @Test
    @DisplayName("a verb form reaches the noun it is formed into")
    void verbToNoun() {
        assertThat(thesaurus.nounsOfVerb("admitted")).contains("admission");
        assertThat(thesaurus.nounsOfVerb("enrolled")).contains("enrollment");
        assertThat(thesaurus.nounsOfVerb("born")).contains("birth");
        assertThat(thesaurus.nounsOfVerb("show")).isEmpty();
        assertThat(thesaurus.nounsOfVerb("order")).isEmpty();
        assertThat(thesaurus.nounsOfVerb("doctor")).isEmpty();
        assertThat(thesaurus.nounsOfVerb("student")).isEmpty();
    }

    @Test
    @DisplayName("related forms cover plurals, derivations, hand-written forms and ending rules")
    void relatedForms() {
        assertThat(thesaurus.relatedForms("surgeon")).contains("surgeon", "surgeons", "surgery", "surgical");
        assertThat(thesaurus.relatedForms("cardiologist")).contains("cardiology");
        assertThat(thesaurus.relatedForms("pediatrician")).contains("pediatrics");
    }

    @Test
    @DisplayName("no instruction word is a synonym or a verb link")
    void noInstructionWordLinks() {
        for (String word : QueryIntentParser.instructionWords()) {
            assertThat(thesaurus.nounsOfVerb(word)).as("nounsOfVerb(%s)", word).isEmpty();
            assertThat(thesaurus.synonymsOf(word)).as("synonymsOf(%s)", word).isEmpty();
            thesaurus.synonymsOf("student").forEach(s -> assertThat(s).isNotEqualTo(word));
        }
    }

    @Test
    @DisplayName("hand-denied pairs never link, either way round")
    void deniedPairs() {
        String[][] pairs = {{"task", "project"}, {"undertaking", "project"}, {"hour", "hr"},
                {"sexual activity", "sex"}, {"last", "close"}, {"title", "statute title"},
                {"ctc", "counterterrorist center"}};
        for (String[] pair : pairs) {
            for (int i = 0; i < 2; i++) {
                String a = pair[i];
                String b = pair[1 - i];
                assertThat(thesaurus.synonymsOf(a)).as("synonymsOf(%s)", a).doesNotContain(b);
                assertThat(thesaurus.synonymsOf(SchemaLexicon.plural(a))).as("synonymsOf(%s)", a).doesNotContain(b);
                assertThat(thesaurus.isKindOf(a, b, 2)).as("%s kind of %s", a, b).isFalse();
            }
        }
        assertThat(thesaurus.synonymsOf("hours")).doesNotContain("hr");
        assertThat(thesaurus.synonymsOf("tasks")).doesNotContain("project");
    }

    @Test
    @DisplayName("an instance is never a kind of its class")
    void instancesAreNotKinds() {
        assertThat(thesaurus.isKindOf("paris", "city", 2)).isFalse();
        assertThat(thesaurus.isKindOf("london", "city", 2)).isFalse();
    }
}
