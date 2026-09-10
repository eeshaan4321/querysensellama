package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SchemaProvisioner
 *
 * Creates the uploaded schema's tables in a database of their own.
 *
 * WHY A SEPARATE DATABASE:
 * The built-in demo schema and query_history live in the connected
 * database. If an upload created its tables there, a user uploading their
 * own `customers` would destroy the demo data, and there would be no way
 * back to the demo schema short of re-seeding by hand. A separate catalog
 * keeps the two apart and makes "reset to built-in" a single reference
 * swap with nothing to undo in the database at all.
 *
 * WHY EVERY STATEMENT IS RE-EMITTED FROM THE PARSED MODEL:
 * The uploaded text is never executed. Each CREATE TABLE here is rebuilt
 * from ParsedTable, whose identifiers SchemaUploadValidator has already
 * constrained to [A-Za-z0-9_]. Executing the file as written would run
 * whatever else it happened to contain — this is what makes "only CREATE
 * TABLE runs" a fact about the code rather than a hope about the input.
 *
 * WHY FOREIGN KEYS ARE ADDED LAST:
 * A dump can declare `orders` before `customers`. Creating every table
 * first and adding constraints afterwards makes declaration order in the
 * uploaded file irrelevant.
 *
 * SAFETY INVARIANT: the only database this class may create, drop into, or
 * alter is uploadCatalog. No statement it emits names the connected
 * database — SchemaProvisionerTest asserts that directly.
 */
@Component
public class SchemaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvisioner.class);

    /**
     * Types MySQL will not accept without a length. A dump can declare them
     * bare when the length came from a default this parser did not read, and
     * `VARCHAR` alone is a syntax error rather than a sensible default.
     */
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

    public void provision(ParsedSchema schema) {
        createDatabase();
        dropExistingTables();
        schema.tables().forEach(this::createTable);
        addForeignKeys(schema);
    }

    private void createDatabase() {
        try {
            jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS `" + uploadCatalog + "`");
        } catch (DataAccessException e) {
            // Without the CREATE privilege the whole feature is unavailable,
            // and a generic 500 would send the user hunting through server
            // logs for what amounts to a one-line grant. Name the grant.
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

        // Dropping parents before children fails on the foreign keys the
        // previous upload created, and there is no ordering that always
        // works. Disabling the check for the drop phase is the standard
        // answer and is scoped to this session.
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
        // UNIQUE is emitted as a column-level constraint rather than a named
        // index: MySQL creates the index either way, and nothing here ever
        // needs to refer to it by name.
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

    private void addForeignKeys(ParsedSchema schema) {
        int index = 0;
        for (KnownSchema.ForeignKey fk : schema.allForeignKeys()) {
            String constraint = "fk_" + fk.fromTable() + "_" + (index++);
            String sql = "ALTER TABLE `" + uploadCatalog + "`.`" + fk.fromTable() + "`"
                    + " ADD CONSTRAINT `" + constraint + "`"
                    + " FOREIGN KEY (`" + fk.fromColumn() + "`)"
                    + " REFERENCES `" + uploadCatalog + "`.`" + fk.toTable() + "` (`" + fk.toColumn() + "`)";
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException e) {
                // A dump can carry a foreign key MySQL will not accept here —
                // mismatched column types being the usual cause. That costs
                // the constraint, not the upload: the tables are still
                // usable, EXPLAIN still runs, and the seeder simply has one
                // less relationship to respect.
                log.warn("Skipping foreign key {}.{} -> {}.{}: {}",
                        fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn(), e.getMessage());
            }
        }
    }
}
