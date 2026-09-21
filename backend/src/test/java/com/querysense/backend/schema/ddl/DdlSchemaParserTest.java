package com.querysense.backend.schema.ddl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DdlSchemaParserTest {

    private DdlSchemaParser parser;

    @BeforeEach
    void setUp() {
        parser = new DdlSchemaParser();
    }

    @Test
    @DisplayName("reads table name, column names and declared types")
    void readsColumns() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (
                  id INT NOT NULL AUTO_INCREMENT,
                  name VARCHAR(120) NOT NULL,
                  email VARCHAR(255) UNIQUE,
                  balance DECIMAL(10,2),
                  PRIMARY KEY (id)
                );
                """);

        assertThat(schema.tables()).hasSize(1);
        ParsedTable customers = schema.tables().get(0);
        assertThat(customers.name()).isEqualTo("customers");
        assertThat(customers.columnNames()).containsExactly("id", "name", "email", "balance");

        ParsedColumn id = customers.column("id").orElseThrow();
        assertThat(id.normalisedType()).isEqualTo("INT");
        assertThat(id.notNull()).isTrue();
        assertThat(id.autoIncrement()).isTrue();
        assertThat(id.primaryKey()).isTrue();

        assertThat(customers.column("name").orElseThrow().firstArgOr(0)).isEqualTo(120);
        assertThat(customers.column("email").orElseThrow().unique()).isTrue();
        assertThat(customers.column("balance").orElseThrow().typeArgs()).containsExactly("10", "2");
    }

    @Test
    @DisplayName("reads a table-level FOREIGN KEY constraint")
    void readsTableLevelForeignKey() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (id INT PRIMARY KEY);
                CREATE TABLE orders (
                  id INT PRIMARY KEY,
                  customer_id INT,
                  CONSTRAINT fk_o_c FOREIGN KEY (customer_id) REFERENCES customers (id)
                );
                """);

        assertThat(schema.allForeignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.fromTable()).isEqualTo("orders");
            assertThat(fk.fromColumn()).isEqualTo("customer_id");
            assertThat(fk.toTable()).isEqualTo("customers");
            assertThat(fk.toColumn()).isEqualTo("id");
        });
    }

    @Test
    @DisplayName("reads an inline column REFERENCES clause")
    void readsInlineForeignKey() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (id INT PRIMARY KEY);
                CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT REFERENCES customers(id));
                """);

        assertThat(schema.allForeignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.fromTable()).isEqualTo("orders");
            assertThat(fk.fromColumn()).isEqualTo("customer_id");
            assertThat(fk.toTable()).isEqualTo("customers");
        });
    }

    @Test
    @DisplayName("strips backticks from identifiers")
    void stripsBackticks() {
        ParsedSchema schema = parser.parse("CREATE TABLE `orders` (`id` INT, `order_date` DATE);");

        assertThat(schema.tables().get(0).name()).isEqualTo("orders");
        assertThat(schema.tables().get(0).columnNames()).containsExactly("id", "order_date");
    }

    @Test
    @DisplayName("keeps ENUM values as type arguments")
    void keepsEnumValues() {
        ParsedSchema schema = parser.parse("CREATE TABLE orders (status ENUM('NEW','PAID','SHIPPED'));");

        ParsedColumn status = schema.tables().get(0).column("status").orElseThrow();
        assertThat(status.normalisedType()).isEqualTo("ENUM");
        assertThat(status.typeArgs()).hasSize(3);
    }

    @Test
    @DisplayName("ignores non-CREATE-TABLE statements without warning about them")
    void ignoresOtherStatements() {
        ParsedSchema schema = parser.parse("""
                DROP TABLE IF EXISTS orders;
                CREATE TABLE orders (id INT PRIMARY KEY);
                INSERT INTO orders VALUES (1);
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.warnings()).isEmpty();
    }

    @Test
    @DisplayName("an unparseable CREATE TABLE becomes a warning, not a failure")
    void unparseableStatementBecomesWarning() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE good (id INT PRIMARY KEY);
                CREATE TABLE bad (((;
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.tables().get(0).name()).isEqualTo("good");
        assertThat(schema.warnings()).hasSize(1);
        assertThat(schema.warnings().get(0)).contains("bad");
    }

    @Test
    @DisplayName("empty or blank input yields no tables and no warnings")
    void emptyInput() {
        assertThat(parser.parse("").tables()).isEmpty();
        assertThat(parser.parse("   ").warnings()).isEmpty();
    }

    @Test
    @DisplayName("parses a mysqldump preamble without choking on its directives")
    void parsesMysqldumpPreamble() {
        ParsedSchema schema = parser.parse("""
                /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
                /*!40101 SET NAMES utf8mb4 */;
                DROP TABLE IF EXISTS `products`;
                CREATE TABLE `products` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `name` varchar(100) DEFAULT NULL,
                  `price` decimal(10,2) DEFAULT NULL,
                  PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.tables().get(0).columnNames()).containsExactly("id", "name", "price");
        assertThat(schema.tables().get(0).column("id").orElseThrow().primaryKey()).isTrue();
        assertThat(schema.warnings()).isEmpty();
    }
}
