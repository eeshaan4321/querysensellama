-- =====================================================================
-- H2 profile seed data — see application-h2.properties.
--
-- WHY SEED AT ALL (an empty database would still let the app start):
-- RuleBasedQueryBuilder.isSafeLocationCondition() asks SchemaMetadataService
-- for the real distinct values of customers.city, and bails to the LLM
-- fallback when a known city appears in the input but not in the WHERE
-- clause it was about to build (the Bug 8/10 safety net). That lookup fails
-- gracefully to an empty list, so with no rows the safety net silently stops
-- firing and the rule engine would claim queries it should have deferred —
-- i.e. you'd be testing different routing than production. These rows exist
-- so the five real city names are present and that guard behaves normally.
--
-- Row COUNT is deliberately small (25, not the 303 of the MySQL seed).
-- Volume only matters for optimizer behaviour — filesort, full scans,
-- cardinality — none of which applies here, since EXPLAIN-based analysis
-- can't run on H2 anyway. What matters for generation is the set of
-- distinct city VALUES, and that's fully represented.
-- =====================================================================

-- The three rows CLAUDE.md's session logs reference by name.
INSERT INTO customers (id, name, city, email) VALUES
    (1, 'Alice',   'Mumbai',    'alice@test.com'),
    (2, 'Bob',     'Pune',      'bob@test.com'),
    (3, 'Charlie', 'Delhi',     'charlie@test.com'),
    (4, 'Diya',    'Bangalore', 'diya@test.com'),
    (5, 'Eshan',   'Ahmedabad', 'eshan@test.com');

-- Remaining rows cycle the same five cities so every city has several
-- customers, and so "customers in <city>" is never a one-row answer.
INSERT INTO customers (id, name, city, email) VALUES
    (6,  'Farhan', 'Mumbai',    'farhan@test.com'),
    (7,  'Gauri',  'Pune',      'gauri@test.com'),
    (8,  'Hardik', 'Delhi',     'hardik@test.com'),
    (9,  'Isha',   'Bangalore', 'isha@test.com'),
    (10, 'Jatin',  'Ahmedabad', 'jatin@test.com'),
    (11, 'Kavya',  'Mumbai',    'kavya@test.com'),
    (12, 'Laksh',  'Pune',      'laksh@test.com'),
    (13, 'Meera',  'Delhi',     'meera@test.com'),
    (14, 'Nikhil', 'Bangalore', 'nikhil@test.com'),
    (15, 'Ojas',   'Ahmedabad', 'ojas@test.com'),
    (16, 'Priya',  'Mumbai',    'priya@test.com'),
    (17, 'Rahul',  'Pune',      'rahul@test.com'),
    (18, 'Sneha',  'Delhi',     'sneha@test.com'),
    (19, 'Tanvi',  'Bangalore', 'tanvi@test.com'),
    (20, 'Utkarsh','Ahmedabad', 'utkarsh@test.com'),
    (21, 'Vidya',  'Mumbai',    'vidya@test.com'),
    (22, 'Yash',   'Pune',      'yash@test.com'),
    (23, 'Zoya',   'Delhi',     'zoya@test.com'),
    (24, 'Aarav',  'Bangalore', 'aarav@test.com'),
    (25, 'Bhavna', 'Ahmedabad', 'bhavna@test.com');

-- ---------------------------------------------------------------------
-- Orders.
--
-- Order counts are chosen so the canonical fallback test query — "customers
-- in mumbai who placed more than 3 orders" — has a MEANINGFUL answer rather
-- than an empty one. A correct query returning zero rows looks exactly like
-- a broken query at a glance.
--
-- Mumbai customers specifically:
--   id 1  (Alice) -> 5 orders   } above the threshold
--   id 6  (Farhan)-> 5 orders   }
--   id 11 (Kavya) -> 4 orders   }
--   id 16 (Priya) -> 3 orders   <- exactly on the boundary, must be EXCLUDED
--   id 21 (Vidya) -> 2 orders   <- below
-- So "more than 3" must return exactly Alice, Farhan and Kavya. That makes
-- an off-by-one (>= vs >) immediately visible instead of plausible.
-- ---------------------------------------------------------------------
INSERT INTO orders (order_id, customer_id, order_date, amount, status) VALUES
    -- Alice (Mumbai) — 5
    (1,  1,  '2026-08-01', 1200.00, 'shipped'),
    (2,  1,  '2026-08-04',  450.50, 'shipped'),
    (3,  1,  '2026-08-11', 2300.00, 'pending'),
    (4,  1,  '2026-08-19',  180.25, 'shipped'),
    (5,  1,  '2026-09-02',  975.00, 'cancelled'),
    -- Farhan (Mumbai) — 5
    (6,  6,  '2026-07-22',  640.00, 'shipped'),
    (7,  6,  '2026-08-03', 1875.75, 'shipped'),
    (8,  6,  '2026-08-14',  220.00, 'pending'),
    (9,  6,  '2026-08-28', 3100.00, 'shipped'),
    (10, 6,  '2026-09-05',  510.40, 'pending'),
    -- Kavya (Mumbai) — 4
    (11, 11, '2026-08-06',  890.00, 'shipped'),
    (12, 11, '2026-08-17', 1450.00, 'cancelled'),
    (13, 11, '2026-08-25',  305.60, 'shipped'),
    (14, 11, '2026-09-01', 2050.00, 'pending'),
    -- Priya (Mumbai) — exactly 3, the boundary case
    (15, 16, '2026-08-09',  760.00, 'shipped'),
    (16, 16, '2026-08-21', 1120.00, 'pending'),
    (17, 16, '2026-09-03',  430.75, 'shipped'),
    -- Vidya (Mumbai) — 2
    (18, 21, '2026-08-13',  980.00, 'shipped'),
    (19, 21, '2026-08-30',  265.00, 'cancelled'),
    -- Other cities, for join/aggregate queries that aren't Mumbai-specific
    (20, 2,  '2026-08-02',  540.00, 'shipped'),
    (21, 2,  '2026-08-15', 1670.00, 'pending'),
    (22, 2,  '2026-08-27',  310.00, 'shipped'),
    (23, 2,  '2026-09-04',  845.00, 'shipped'),
    (24, 3,  '2026-08-08', 2200.00, 'shipped'),
    (25, 3,  '2026-08-20',  395.50, 'pending'),
    (26, 4,  '2026-08-12',  720.00, 'shipped'),
    (27, 5,  '2026-08-24', 1340.00, 'cancelled'),
    (28, 7,  '2026-08-05',  610.00, 'shipped'),
    (29, 7,  '2026-08-18',  455.00, 'shipped'),
    (30, 8,  '2026-08-29', 1980.00, 'pending'),
    (31, 9,  '2026-09-06',  275.00, 'shipped'),
    (32, 10, '2026-08-10',  830.00, 'shipped'),
    (33, 12, '2026-08-23', 1510.00, 'pending'),
    (34, 13, '2026-08-31',  690.00, 'shipped'),
    (35, 14, '2026-09-07',  405.00, 'cancelled'),
    (36, 17, '2026-08-16', 1250.00, 'shipped'),
    (37, 18, '2026-08-26',  580.00, 'shipped'),
    (38, 19, '2026-09-08',  915.00, 'pending'),
    (39, 22, '2026-08-07', 1090.00, 'shipped'),
    (40, 23, '2026-08-22',  365.00, 'shipped');

-- Customers 15, 20, 24 and 25 deliberately have NO orders, so "customers with
-- no orders" (a LEFT JOIN / NOT EXISTS shape, which the rule engine defers
-- to the LLM) has a real answer to return.
