package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EditsTest {

    @Test
    @DisplayName("one insertion, deletion, substitution or adjacent swap is one edit")
    void oneEdit() {
        assertThat(Edits.withinOne("custmers", "customers")).isTrue();
        assertThat(Edits.withinOne("customerss", "customers")).isTrue();
        assertThat(Edits.withinOne("delhy", "delhi")).isTrue();
        assertThat(Edits.withinOne("emial", "email")).isTrue();
        assertThat(Edits.withinOne("mumbia", "mumbai")).isTrue();
    }

    @Test
    @DisplayName("identical words and two edits are not one edit")
    void notOneEdit() {
        assertThat(Edits.withinOne("pune", "pune")).isFalse();
        assertThat(Edits.withinOne("custmrs", "customers")).isFalse();
        assertThat(Edits.withinOne("cat", "dog")).isFalse();
    }
}
