-- =====================================================================
-- MySQL development schema for QuerySense.
--
-- WHY THIS FILE EXISTS:
-- The h2/ scripts beside this one let a machine run query GENERATION with
-- no MySQL at all, but /api/query/analyze and /api/query/schema-advisor
-- read MySQL's own EXPLAIN columns (type / key / rows / Extra), which H2
-- does not produce. A developer whose `querysense` database is missing
-- cannot boot on the default profile, falls back to the h2 profile to get
-- the app running, and from then on every analyze call returns
-- explainFailed=true with a warning that blames their SQL. This script
-- removes the reason to reach for that profile.
--
-- HOW TO RUN (from the repo root):
--   mysql -h 127.0.0.1 -P 3306 -u root -p < src/main/resources/mysql/schema.sql
--   mysql -h 127.0.0.1 -P 3306 -u root -p < src/main/resources/mysql/data.sql
-- Then start the backend on the DEFAULT profile (no -Dspring-boot.run.profiles=h2).
--
-- IDEMPOTENT AND NON-DESTRUCTIVE, DELIBERATELY. Every statement is
-- IF NOT EXISTS and there is no DROP anywhere: on a machine that already
-- has these tables this file is a no-op that cannot cost anyone their
-- data, which is the only way a setup script is safe to re-run when you
-- are unsure whether it was run before. Seeding rows is data.sql's job,
-- kept separate for the same reason.
--
-- NOT loaded by Spring. spring.sql.init only reads locations a profile
-- names explicitly, and no profile names this folder — it is run by hand,
-- once per developer machine.
--
-- query_history is deliberately absent: it is the one real JPA entity, so
-- spring.jpa.hibernate.ddl-auto=update creates and migrates it at startup.
-- Defining it here too would let the two definitions drift apart.
--
-- Mirrors schema/KnownSchema.java, which is what both RuleBasedQueryBuilder
-- and OllamaService's schema-grounded prompt read. A column that differs
-- here is a column the rule engine is tested against but does not exist.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS querysense
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE querysense;

CREATE TABLE IF NOT EXISTS customers (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    city  VARCHAR(100),
    email VARCHAR(150) UNIQUE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS orders (
    order_id    INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_date  DATE,
    amount      DECIMAL(10,2),
    status      VARCHAR(50),
    FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;

-- =====================================================================
-- NO INDEX ON customers.city, DELIBERATELY.
--
-- Index state is the variable this project's central proof-of-concept
-- manipulates. `SELECT * FROM customers WHERE city = 'Mumbai'` has to
-- report accessType=ALL / usesIndex=false and produce an INDEX
-- recommendation first, so that
--     CREATE INDEX idx_city ON customers(city);
-- can then be shown flipping it to accessType=ref / usesIndex=true with
-- the recommendation gone. Creating idx_city here would delete the "before"
-- half of that demo and leave the Schema Advisor with nothing to say.
--
-- The indexes that do exist are structural rather than tuning decisions:
-- PRIMARY on customers.id and orders.order_id, UNIQUE on customers.email,
-- and the index InnoDB creates automatically for the orders.customer_id
-- foreign key. order_date, amount and status are intentionally unindexed.
-- =====================================================================
