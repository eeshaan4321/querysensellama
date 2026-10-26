package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SynonymListTest {

    @Test
    @DisplayName("the built-in list loads, and a group answers in both directions")
    void loadsAndAnswers() {
        Synonyms synonyms = Synonyms.builtIn();

        assertThat(synonyms.of("client")).contains("customer");
        assertThat(synonyms.of("customer")).contains("client", "buyer");
        assertThat(synonyms.of("customer")).doesNotContain("customer");
        assertThat(synonyms.of("nothing")).isEmpty();
    }

    @Test
    @DisplayName("no synonym is a word the parser reads as an instruction")
    void noInstructionWords() {
        Set<String> clash = new HashSet<>(Synonyms.builtIn().allWords());
        clash.retainAll(QueryIntentParser.instructionWords());

        assertThat(clash).isEmpty();
    }

    @Test
    @DisplayName("no word sits in two groups")
    void noWordInTwoGroups() {
        Synonyms synonyms = new Synonyms(List.of(List.of("a1", "b1"), List.of("c1", "d1")));
        assertThat(synonyms.allWords()).containsExactlyInAnyOrder("a1", "b1", "c1", "d1");

        List<List<String>> groups = Synonyms.builtInGroups();
        Set<String> seen = new HashSet<>();
        groups.forEach(group -> group.forEach(word -> assertThat(seen.add(word)).as(word).isTrue()));
    }

    @Test
    @DisplayName("common schema domains are covered")
    void domainVocabulary() {
        Synonyms s = Synonyms.builtIn();

        assertThat(s.of("teacher")).contains("faculty", "instructor");
        assertThat(s.of("teacher")).doesNotContain("professor", "lecturer");
        assertThat(s.of("pupil")).contains("student", "learner");
        assertThat(s.of("physician")).contains("doctor");
        assertThat(Synonyms.builtInGroups()).hasSizeGreaterThanOrEqualTo(45);
    }
}
