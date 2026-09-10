package com.querysense.backend.analysis;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.function.Function;

/**
 * ExplainExecutor
 *
 * WHY THIS CLASS EXISTS:
 * EXPLAIN has to run against whichever database the active schema
 * describes. When the user has uploaded a schema, that is
 * `querysense_uploads`, not the database spring.datasource.url points at.
 *
 * WHY A ConnectionCallback RATHER THAN A SECOND DataSource:
 * Switching catalog and then running EXPLAIN only works if both statements
 * run on the same physical connection, and a pooled JdbcTemplate gives no
 * such guarantee across two separate calls. ConnectionCallback holds one
 * connection for the whole unit of work. The scoped JdbcTemplate built over
 * it is a non-closing wrapper, so callers keep JdbcTemplate's RowMapper
 * support and Spring's exception translation instead of hand-rolling JDBC.
 *
 * WHY THE CATALOG IS RESTORED IN A finally:
 * The connection goes back to the pool afterwards. Leaving it pointed at
 * the wrong database would give the NEXT request a plan for tables the user
 * never asked about — a wrong answer rather than an error, which is the
 * worst failure shape available. The restore is unconditional.
 *
 * WHY THE BUILT-IN SCHEMA IS A NO-OP:
 * activeSchema.catalog() is null for the built-in schema, and this class
 * then runs the work with no catalog handling whatsoever — the same
 * statements, on the same connection, as before this class existed. That is
 * the "EXPLAIN is unchanged" guarantee for the demo path, and
 * ExplainExecutorTest asserts setCatalog is never called there rather than
 * leaving it as a claim.
 */
@Component
public class ExplainExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ActiveSchemaProvider activeSchema;

    public ExplainExecutor(JdbcTemplate jdbcTemplate, ActiveSchemaProvider activeSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeSchema = activeSchema;
    }

    /**
     * Runs {@code work} against a JdbcTemplate bound to a single connection
     * pointing at the active schema's catalog.
     *
     * @param work receives a connection-scoped JdbcTemplate. Do not retain
     *             it — it is only valid for the duration of the call.
     */
    public <T> T withActiveCatalog(Function<JdbcTemplate, T> work) {
        String target = activeSchema.catalog();
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            if (target == null) {
                return work.apply(scoped(connection));
            }
            String original = connection.getCatalog();
            boolean switched = !target.equals(original);
            if (switched) {
                connection.setCatalog(target);
            }
            try {
                return work.apply(scoped(connection));
            } finally {
                if (switched) {
                    connection.setCatalog(original);
                }
            }
        });
    }

    /** suppressClose=true: closing this wrapper must not close the pooled connection. */
    private static JdbcTemplate scoped(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
