-- =====================================================================
-- MySQL development seed data for QuerySense. Run after schema.sql:
--   mysql -h 127.0.0.1 -P 3306 -u root -p < src/main/resources/mysql/data.sql
--
-- WHY SEEDING IS NOT OPTIONAL:
-- Two separate parts of the system read real rows, and both fail quietly
-- rather than loudly when there are none.
--   1. EXPLAIN on an empty table is degenerate. MySQL reports plans like
--      "no matching row in const table" and a rows estimate of 0 or 1, so
--      accessType, hasFullTableScan and usesFilesort stop describing
--      anything real — the analysis engine returns successfully and says
--      nothing useful, which is harder to notice than an error.
--   2. RuleBasedQueryBuilder.isSafeLocationCondition() asks
--      SchemaMetadataService for the live DISTINCT values of
--      customers.city and defers to the LLM when a real city appears in
--      the input but not in the WHERE clause it was about to build. That
--      lookup degrades to an empty list on an empty table, so the safety
--      net silently stops firing and the rule engine starts claiming
--      queries it should have handed to the fallback.
--
-- WHY 303 CUSTOMERS AND NOT 3:
-- Row volume changes what the optimizer decides, and this project reports
-- those decisions. On a 3-row table MySQL scans everything regardless of
-- indexes, so a full table scan proves nothing and "the table was too
-- small to matter" is a fair objection to every finding. 303 is the count
-- CLAUDE.md's filesort investigation used for exactly that reason, so
-- reproducing it keeps this machine's EXPLAIN output comparable with the
-- results already written up.
--
-- WHY THE VALUES ARE COMPUTED, NOT RANDOM:
-- No RAND() anywhere. Every machine that runs this gets identical rows, so
-- an EXPLAIN result recorded on one laptop is reproducible on another —
-- the same commitment the LLM fallback makes with temperature 0 and a
-- fixed seed.
--
-- SAFE TO RE-RUN. INSERT IGNORE rather than DELETE-then-INSERT: this
-- script never removes rows a teammate already has, and running it twice
-- changes nothing. If you want a clean slate, truncate by hand — that
-- should be a decision, not a side effect of running a setup script.
-- =====================================================================

USE querysense;

-- The named rows CLAUDE.md's session logs refer to by name, at the same
-- ids, so 'alice@test.com' still resolves in the documented examples.
INSERT IGNORE INTO customers (id, name, city, email) VALUES
    (1, 'Alice',   'Mumbai',    'alice@test.com'),
    (2, 'Bob',     'Pune',      'bob@test.com'),
    (3, 'Charlie', 'Delhi',     'charlie@test.com'),
    (4, 'Diya',    'Bangalore', 'diya@test.com'),
    (5, 'Eshan',   'Ahmedabad', 'eshan@test.com');

-- Customers 6..303, cycling the same five cities so each city has ~60 rows
-- and no city filter is a one-row answer.
INSERT IGNORE INTO customers (id, name, city, email)
WITH RECURSIVE seq(n) AS (
    SELECT 6
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 303
)
SELECT
    n,
    CONCAT('Customer ', n),
    ELT(1 + (n % 5), 'Mumbai', 'Pune', 'Delhi', 'Bangalore', 'Ahmedabad'),
    CONCAT('customer', n, '@test.com')
FROM seq;

-- 900 orders spread over all 303 customers and the last year, so JOINs
-- have a realistic ~3-orders-per-customer fan-out and GROUP BY queries
-- return many groups rather than one.
INSERT IGNORE INTO orders (order_id, customer_id, order_date, amount, status)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 900
)
SELECT
    n,
    1 + (n % 303),
    DATE_SUB(DATE '2026-09-09', INTERVAL (n % 365) DAY),
    ROUND(50 + ((n * 37) % 5000) / 10, 2),
    ELT(1 + (n % 4), 'PENDING', 'SHIPPED', 'DELIVERED', 'CANCELLED')
FROM seq;

-- EXPLAIN's `rows` column is read straight from InnoDB's sampled
-- statistics. Refreshing them here means the first analysis after seeding
-- reports real cardinality instead of the stale estimate from when these
-- tables were empty.
ANALYZE TABLE customers, orders;

SELECT
    (SELECT COUNT(*) FROM customers) AS customers_total,
    (SELECT COUNT(*) FROM orders)    AS orders_total,
    (SELECT COUNT(DISTINCT city) FROM customers) AS distinct_cities;
