package com.querysense.backend.analysis;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExplainExecutorTest
 *
 * The first test here is the regression guard for the whole schema-upload
 * feature. EXPLAIN worked before this feature existed, and the one way the
 * feature could break it is by switching the catalog on a pooled connection
 * when it had no business doing so — which would hand the next borrower a
 * connection pointing at the wrong database, and produce a wrong-but-
 * plausible plan rather than an error.
 *
 * builtInSchemaNeverSwitchesCatalog pins the guarantee: on the built-in
 * schema, not one call to setCatalog.
 */
class ExplainExecutorTest {

    private JdbcTemplate jdbcTemplate;
    private Connection connection;
    private ActiveSchemaProvider activeSchema;
    private ExplainExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("querysense");

        // Make jdbcTemplate.execute(ConnectionCallback) actually invoke the
        // callback against our mock connection — the catalog handling inside
        // that callback is the thing under test.
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<Object> cb = inv.getArgument(0);
            return cb.doInConnection(connection);
        });

        activeSchema = new ActiveSchemaProvider();
        executor = new ExplainExecutor(jdbcTemplate, activeSchema);
    }

    @Test
    @DisplayName("built-in schema: setCatalog is never called")
    void builtInSchemaNeverSwitchesCatalog() throws Exception {
        String result = executor.withActiveCatalog(jdbc -> "ran");

        assertThat(result).isEqualTo("ran");
        verify(connection, never()).setCatalog(any());
    }

    @Test
    @DisplayName("uploaded schema: switches to the upload catalog and restores the original")
    void uploadedSchemaSwitchesAndRestores() throws Exception {
        activeSchema.publish(uploaded());

        executor.withActiveCatalog(jdbc -> "ran");

        var order = inOrder(connection);
        order.verify(connection).setCatalog("querysense_uploads");
        order.verify(connection).setCatalog("querysense");
    }

    @Test
    @DisplayName("the original catalog is restored even when the work throws")
    void restoresCatalogOnException() throws Exception {
        activeSchema.publish(uploaded());

        assertThatThrownBy(() -> executor.withActiveCatalog(jdbc -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(connection).setCatalog("querysense");
    }

    @Test
    @DisplayName("no switch when the connection is already on the target catalog")
    void noSwitchWhenAlreadyOnTarget() throws Exception {
        when(connection.getCatalog()).thenReturn("querysense_uploads");
        activeSchema.publish(uploaded());

        executor.withActiveCatalog(jdbc -> "ran");

        verify(connection, never()).setCatalog(any());
    }

    private static SchemaSnapshot uploaded() {
        return new SchemaSnapshot(
                "upload-1", "test.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("invoices", List.of("id"))),
                List.of(), Map.of(), Instant.now());
    }
}
