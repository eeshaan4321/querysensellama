package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.SchemaUploadValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KnownMongoSchemaMatchesSeedTest {

    private static ParsedSchema demo;

    @BeforeAll
    static void parseDemo() throws IOException {
        demo = new JsonSchemaUploadParser().parse(
                new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the JSON declares exactly KnownMongoSchema's collections, fields and embedded arrays, in order")
    void collectionsMatch() {
        assertThat(demo.toKnownTables()).containsExactlyElementsOf(KnownMongoSchema.TABLES);
    }

    @Test
    @DisplayName("the JSON's references are KnownMongoSchema's foreign keys")
    void referencesMatch() {
        assertThat(demo.allForeignKeys()).containsExactlyInAnyOrderElementsOf(KnownMongoSchema.FOREIGN_KEYS);
    }

    @Test
    @DisplayName("the JSON's indexes are KnownMongoSchema's indexes")
    void indexesMatch() {
        assertThat(demo.indexes()).containsExactlyInAnyOrderElementsOf(KnownMongoSchema.INDEXES);
    }

    @Test
    @DisplayName("the JSON's field types are KnownMongoSchema's field types")
    void fieldTypesMatch() {
        assertThat(demo.fieldTypes()).isEqualTo(KnownMongoSchema.FIELD_TYPES);
    }

    @Test
    @DisplayName("the demo passes the upload validator without warnings")
    void demoIsAValidUpload() {
        assertThat(demo.warnings()).isEmpty();
        assertThatCode(() -> new SchemaUploadValidator(40).validate(demo, Dialect.MONGODB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the built-in snapshot carries the indexes and field types")
    void builtInCarriesThem() {
        SchemaSnapshot builtIn = KnownMongoSchema.builtIn();

        assertThat(builtIn.indexes()).isEqualTo(KnownMongoSchema.INDEXES);
        assertThat(builtIn.fieldTypesFor("orders")).containsEntry("items.unit_price", "decimal");
    }

    @Test
    @DisplayName("the JSON's enums are KnownMongoSchema's enums")
    void enumsMatch() {
        assertThat(demo.enums()).isEqualTo(KnownMongoSchema.ENUMS);
    }
}
