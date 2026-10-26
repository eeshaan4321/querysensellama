package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Creates an uploaded schema's tables in the upload database. */
@Component
public class SchemaProvisioner implements SchemaProvisioning {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvisioner.class);

    private static final Set<String> NEEDS_LENGTH = Set.of("VARCHAR", "CHAR", "VARBINARY", "BINARY");

    private final JdbcTemplate jdbcTemplate;
    private final String uploadCatalog;

    public SchemaProvisioner(JdbcTemplate jdbcTemplate,
                             @Value("${querysense.schema.upload.catalog:querysense_uploads}") String uploadCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadCatalog = uploadCatalog;
    }

    public String uploadCatalog() {
        return uploadCatalog;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public String uploadDatabase() {
        return uploadCatalog;
    }

    @Override
    public void provision(ParsedSchema schema) {
        createDatabase();
        dropExistingTables();
        schema.tables().forEach(this::createTable);
    }

    @Override
    public List<String> finish(ParsedSchema schema) {
        return addForeignKeys(schema);
    }

    private void createDatabase() {
        try {
            jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS `" + uploadCatalog + "`");
        } catch (DataAccessException e) {
            throw new SchemaUploadException(
                    "Could not create the database '" + uploadCatalog + "'. The account QuerySense connects "
                            + "with needs the CREATE privilege: "
                            + "GRANT ALL PRIVILEGES ON `" + uploadCatalog + "`.* TO <user>@<host>;", e);
        }
    }

    private void dropExistingTables() {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, uploadCatalog);

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : existing) {
                jdbcTemplate.execute("DROP TABLE IF EXISTS `" + uploadCatalog + "`.`" + table + "`");
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        log.info("Cleared {} table(s) from '{}' before provisioning", existing.size(), uploadCatalog);
    }

    private void createTable(ParsedTable table) {
        String columns = table.columns().stream().map(this::columnClause).collect(Collectors.joining(",\n  "));
        String primaryKey = table.columns().stream()
                .filter(ParsedColumn::primaryKey)
                .map(c -> "`" + c.name() + "`")
                .collect(Collectors.joining(", "));

        StringBuilder ddl = new StringBuilder("CREATE TABLE `")
                .append(uploadCatalog).append("`.`").append(table.name()).append("` (\n  ")
                .append(columns);
        if (!primaryKey.isEmpty()) {
            ddl.append(",\n  PRIMARY KEY (").append(primaryKey).append(")");
        }
        ddl.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        jdbcTemplate.execute(ddl.toString());
    }

    private String columnClause(ParsedColumn column) {
        StringBuilder clause = new StringBuilder("`").append(column.name()).append("` ").append(typeClause(column));
        if (column.notNull() || column.primaryKey()) {
            clause.append(" NOT NULL");
        }
        if (column.autoIncrement()) {
            clause.append(" AUTO_INCREMENT");
        }
        if (column.unique() && !column.primaryKey()) {
            clause.append(" UNIQUE");
        }
        return clause.toString();
    }

    private String typeClause(ParsedColumn column) {
        String type = column.normalisedType();
        if (column.typeArgs().isEmpty()) {
            return NEEDS_LENGTH.contains(type) ? type + "(255)" : type;
        }
        return type + "(" + String.join(", ", column.typeArgs()) + ")";
    }

    private List<String> addForeignKeys(ParsedSchema schema) {
        List<String> warnings = new ArrayList<>();
        List<KnownSchema.ForeignKey> foreignKeys = schema.allForeignKeys();
        if (foreignKeys.isEmpty()) {
            return warnings;
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    int index = 0;
                    for (KnownSchema.ForeignKey fk : foreignKeys) {
                        String sql = "ALTER TABLE `" + uploadCatalog + "`.`" + fk.fromTable() + "`"
                                + " ADD CONSTRAINT `fk_" + fk.fromTable() + "_" + (index++) + "`"
                                + " FOREIGN KEY (`" + fk.fromColumn() + "`)"
                                + " REFERENCES `" + uploadCatalog + "`.`" + fk.toTable() + "` (`" + fk.toColumn() + "`)";
                        try {
                            statement.execute(sql);
                        } catch (SQLException e) {
                            log.warn("Skipping foreign key {}.{} -> {}.{}: {}",
                                    fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn(), e.getMessage());
                            warnings.add("The foreign key " + fk.fromTable() + "." + fk.fromColumn() + " -> "
                                    + fk.toTable() + "." + fk.toColumn() + " could not be added to the sample database, "
                                    + "so MySQL does not enforce it -- " + rootReason(e));
                        }
                    }
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
        return warnings;
    }

    private static String rootReason(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? String.valueOf(e.getMessage()) : cause.getMessage().trim();
    }
}
