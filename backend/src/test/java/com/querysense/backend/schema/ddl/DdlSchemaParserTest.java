package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
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

    @Test
    @DisplayName("aka: in table and column COMMENTs declares aliases; ordinary comments don't")
    void commentAliases() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (
                  id INT PRIMARY KEY,
                  city VARCHAR(60) NOT NULL COMMENT 'Where they live. aka: town, locality',
                  email VARCHAR(80) COMMENT "aka: mail",
                  phone VARCHAR(20) COMMENT 'Mobile number'
                ) ENGINE=InnoDB COMMENT='Registered buyers. aka: clients, patrons';
                CREATE TABLE notes (id INT PRIMARY KEY) COMMENT 'internal';
                """);

        SchemaAliases.TableAliases customers = schema.aliases().get("customers");
        assertThat(customers.table()).containsExactly("clients", "patrons");
        assertThat(customers.column("city")).containsExactly("town", "locality");
        assertThat(customers.column("email")).containsExactly("mail");
        assertThat(customers.column("phone")).isEmpty();
        assertThat(schema.aliases()).doesNotContainKey("notes");
    }

    @Test
    @DisplayName("foreign keys added later with ALTER TABLE are read, including a self-reference")
    void readsAlterTableForeignKeys() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE EMPLOYEE (Ssn CHAR(9) NOT NULL, Super_ssn CHAR(9), Dno INT NOT NULL, PRIMARY KEY (Ssn));
                CREATE TABLE DEPARTMENT (Dnumber INT NOT NULL, Mgr_ssn CHAR(9) NOT NULL, PRIMARY KEY (Dnumber),
                  FOREIGN KEY (Mgr_ssn) REFERENCES EMPLOYEE(Ssn));
                INSERT INTO EMPLOYEE VALUES ('123456789', NULL, 5);
                ALTER TABLE EMPLOYEE
                 ADD CONSTRAINT Emp_super FOREIGN KEY (Super_ssn) REFERENCES EMPLOYEE(Ssn);
                ALTER TABLE `EMPLOYEE` ADD CONSTRAINT `Emp_dno` FOREIGN KEY (`Dno`) REFERENCES `DEPARTMENT` (`Dnumber`);
                """);

        assertThat(schema.warnings()).isEmpty();
        assertThat(schema.table("EMPLOYEE").orElseThrow().foreignKeys()).containsExactly(
                new KnownSchema.ForeignKey("EMPLOYEE", "Super_ssn", "EMPLOYEE", "Ssn"),
                new KnownSchema.ForeignKey("EMPLOYEE", "Dno", "DEPARTMENT", "Dnumber"));
        assertThat(schema.samplesFor("EMPLOYEE")).hasSize(1);
    }

    @Test
    @DisplayName("the same foreign key declared twice is kept once")
    void repeatedForeignKeyKeptOnce() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE EMPLOYEE (Ssn CHAR(9) PRIMARY KEY, Super_ssn CHAR(9));
                CREATE TABLE DEPARTMENT (Dnumber INT PRIMARY KEY, Mgr_ssn CHAR(9),
                  FOREIGN KEY (Mgr_ssn) REFERENCES EMPLOYEE(Ssn));
                ALTER TABLE DEPARTMENT ADD CONSTRAINT Dep_emp FOREIGN KEY (Mgr_ssn) REFERENCES EMPLOYEE(Ssn);
                ALTER TABLE EMPLOYEE ADD CONSTRAINT Emp_emp FOREIGN KEY (Super_ssn) REFERENCES EMPLOYEE(Ssn);
                ALTER TABLE EMPLOYEE ADD CONSTRAINT Emp_super FOREIGN KEY (Super_ssn) REFERENCES EMPLOYEE(Ssn);
                """);

        assertThat(schema.allForeignKeys()).containsExactly(
                new KnownSchema.ForeignKey("EMPLOYEE", "Super_ssn", "EMPLOYEE", "Ssn"),
                new KnownSchema.ForeignKey("DEPARTMENT", "Mgr_ssn", "EMPLOYEE", "Ssn"));
    }

    @Test
    @DisplayName("pg_dump's ALTER TABLE ONLY primary and foreign keys are read; OWNER TO is ignored quietly")
    void readsPgDumpAlterConstraints() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE public.customers (id integer NOT NULL, email character varying(80));
                ALTER TABLE public.customers OWNER TO postgres;
                CREATE TABLE public.orders (id integer NOT NULL, customer_id integer);
                ALTER TABLE ONLY public.customers ADD CONSTRAINT customers_pkey PRIMARY KEY (id);
                ALTER TABLE ONLY public.customers ADD CONSTRAINT customers_email_key UNIQUE (email);
                ALTER TABLE ONLY public.orders
                    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
                ALTER TABLE ONLY public.orders
                    ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customers(id);
                """);

        assertThat(schema.warnings()).isEmpty();
        assertThat(schema.table("customers").orElseThrow().column("id").orElseThrow().primaryKey()).isTrue();
        assertThat(schema.table("customers").orElseThrow().column("email").orElseThrow().unique()).isTrue();
        assertThat(schema.table("orders").orElseThrow().column("id").orElseThrow().primaryKey()).isTrue();
        assertThat(schema.allForeignKeys()).containsExactly(
                new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id"));
    }

    @Test
    @DisplayName("several ADD clauses in one ALTER TABLE are each read")
    void readsSeveralAddClauses() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE a (id INT, b_id INT, c_id INT);
                CREATE TABLE b (id INT PRIMARY KEY);
                CREATE TABLE c (id INT PRIMARY KEY);
                ALTER TABLE a ADD PRIMARY KEY (id), ADD KEY idx_b (b_id),
                  ADD CONSTRAINT fk_b FOREIGN KEY (b_id) REFERENCES b (id) ON DELETE CASCADE,
                  ADD CONSTRAINT fk_c FOREIGN KEY (c_id) REFERENCES c (id);
                """);

        assertThat(schema.table("a").orElseThrow().column("id").orElseThrow().primaryKey()).isTrue();
        assertThat(schema.allForeignKeys()).extracting(KnownSchema.ForeignKey::toTable).containsExactly("b", "c");
        assertThat(schema.warnings()).singleElement().asString().contains("'a'").contains("ADD KEY idx_b");
    }

    @Test
    @DisplayName("an ALTER TABLE that can't be applied is named in a warning, not dropped silently")
    void unappliedAlterWarns() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE a (id INT PRIMARY KEY);
                ALTER TABLE a ADD COLUMN note VARCHAR(20);
                ALTER TABLE missing ADD CONSTRAINT fk FOREIGN KEY (x) REFERENCES a (id);
                """);

        assertThat(schema.warnings()).hasSize(2);
        assertThat(schema.warnings().get(0)).contains("'a'").contains("ADD COLUMN note");
        assertThat(schema.warnings().get(1)).contains("'missing'");
        assertThat(schema.allForeignKeys()).isEmpty();
    }

    @Test
    @DisplayName("a table-level UNIQUE (col) marks the column unique")
    void tableLevelUnique() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE DEPARTMENT (Dname VARCHAR(15) NOT NULL, Dnumber INT NOT NULL,
                  PRIMARY KEY (Dnumber), UNIQUE (Dname));
                """);

        assertThat(schema.tables().get(0).column("Dname").orElseThrow().unique()).isTrue();
        assertThat(schema.warnings()).isEmpty();
    }
}
