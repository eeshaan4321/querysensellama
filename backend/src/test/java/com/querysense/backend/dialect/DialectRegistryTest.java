package com.querysense.backend.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectRegistryTest {

    private record Impl(Dialect dialect) implements DialectAware {}

    @Test
    @DisplayName("returns the implementation registered for each dialect")
    void looksUpByDialect() {
        Impl mysql = new Impl(Dialect.MYSQL);
        Impl mongo = new Impl(Dialect.MONGODB);

        DialectRegistry<Impl> registry = new DialectRegistry<>(List.of(mongo, mysql), "emitter");

        assertThat(registry.get(Dialect.MYSQL)).isSameAs(mysql);
        assertThat(registry.get(Dialect.MONGODB)).isSameAs(mongo);
    }

    @Test
    @DisplayName("a dialect with no implementation fails at construction, naming the role")
    void missingDialectFailsFast() {
        assertThatThrownBy(() -> new DialectRegistry<>(List.of(new Impl(Dialect.MYSQL)), "emitter"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emitter")
                .hasMessageContaining("MONGODB");
    }

    @Test
    @DisplayName("two implementations for one dialect fail at construction")
    void duplicateFailsFast() {
        assertThatThrownBy(() -> new DialectRegistry<>(List.of(
                new Impl(Dialect.MYSQL), new Impl(Dialect.MYSQL), new Impl(Dialect.MONGODB)), "emitter"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL");
    }
}
