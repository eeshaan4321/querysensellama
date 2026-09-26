package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.function.Function;

/** Runs EXPLAIN statements against the database of the active schema. */
@Component
public class ExplainExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ActiveSchemaProvider activeSchema;

    public ExplainExecutor(JdbcTemplate jdbcTemplate, ActiveSchemaProvider activeSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeSchema = activeSchema;
    }

    public <T> T withActiveCatalog(Function<JdbcTemplate, T> work) {
        String target = activeSchema.catalog(Dialect.MYSQL);
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

    private static JdbcTemplate scoped(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
