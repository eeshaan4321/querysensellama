package com.querysense.backend.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectTest {

    @Test
    @DisplayName("the strings the frontend sends parse, in any case")
    void parsesFrontendStrings() {
        assertThat(Dialect.from("MySQL")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.from("mongodb")).isEqualTo(Dialect.MONGODB);
        assertThat(Dialect.from(" MONGODB ")).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("an unknown database type is refused with a message naming both choices")
    void unknownIsRefused() {
        assertThatThrownBy(() -> Dialect.from("postgres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("MySQL")
                .hasMessageContaining("MongoDB");
        assertThatThrownBy(() -> Dialect.from(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the label is the string history and responses already use")
    void labelMatchesStoredStrings() {
        assertThat(Dialect.MYSQL.label()).isEqualTo("MySQL");
        assertThat(Dialect.MONGODB.label()).isEqualTo("MongoDB");
    }
}
