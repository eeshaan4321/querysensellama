package com.querysense.backend.schema;

import com.querysense.backend.schema.ddl.DdlSchemaParser;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in schema and the scripts that create it must describe the same tables:
 * nothing declared here may be missing from the database the demo actually runs on.
 */
class KnownSchemaMatchesSeedScriptsTest {

    private final DdlSchemaParser parser = new DdlSchemaParser();

    @ParameterizedTest(name = "{0} declares exactly the tables KnownSchema does")
    @ValueSource(strings = {"mysql/schema.sql", "h2/schema.sql"})
    void declaresTheSameTables(String script) throws IOException {
        ParsedSchema parsed = parse(script);

        assertThat(parsed.tables()).extracting(ParsedTable::name)
                .as("%s creates tables KnownSchema does not declare, or omits ones it does", script)
                .containsExactlyInAnyOrderElementsOf(KnownSchema.tableNames());
    }

    @ParameterizedTest(name = "{0} declares exactly the columns KnownSchema does")
    @ValueSource(strings = {"mysql/schema.sql", "h2/schema.sql"})
    void declaresTheSameColumns(String script) throws IOException {
        ParsedSchema parsed = parse(script);

        for (KnownSchema.Table declared : KnownSchema.TABLES) {
            ParsedTable created = parsed.table(declared.name()).orElseThrow(() ->
                    new AssertionError(script + " never creates '" + declared.name() + "'"));

            assertThat(created.columnNames())
                    .as("'%s' in %s", declared.name(), script)
                    .containsExactlyInAnyOrderElementsOf(declared.columns());
        }
    }

    @ParameterizedTest(name = "{0} declares exactly the foreign keys KnownSchema does")
    @ValueSource(strings = {"mysql/schema.sql", "h2/schema.sql"})
    void declaresTheSameForeignKeys(String script) throws IOException {
        ParsedSchema parsed = parse(script);

        assertThat(parsed.allForeignKeys())
                .as("the join graph in %s", script)
                .containsExactlyInAnyOrderElementsOf(KnownSchema.FOREIGN_KEYS);
    }

    private ParsedSchema parse(String resource) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s is on the classpath", resource).isNotNull();
            return parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
