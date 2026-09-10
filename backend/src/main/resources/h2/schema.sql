-- =====================================================================
-- H2 profile only — see application-h2.properties.
--
-- Mirrors KnownSchema.java, which is the single source of truth that both
-- RuleBasedQueryBuilder and OllamaService's schema-grounded prompt read
-- from. If a column changes there, change it here too, or the rule engine
-- will be tested against a schema the prompt doesn't describe.
--
-- No AUTO_INCREMENT: nothing in the application ever inserts into these
-- tables (they are read-only reference data for the rule engine's live
-- safety net), so explicit ids keep this portable across H2/MySQL dialect
-- differences rather than depending on identity-column behaviour.
--
-- No secondary indexes, deliberately — same reasoning as the MySQL seed
-- script: index state is what the before/after EXPLAIN demo manipulates,
-- and that demo needs real MySQL anyway.
-- =====================================================================

DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
    id    INT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    city  VARCHAR(100),
    email VARCHAR(150)
);

CREATE TABLE orders (
    order_id    INT PRIMARY KEY,
    customer_id INT,
    order_date  DATE,
    amount      DECIMAL(10,2),
    status      VARCHAR(30),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);
