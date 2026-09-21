USE querysense;

INSERT IGNORE INTO customers (id, name, city, email) VALUES
    (1, 'Alice',   'Mumbai',    'alice@test.com'),
    (2, 'Bob',     'Pune',      'bob@test.com'),
    (3, 'Charlie', 'Delhi',     'charlie@test.com'),
    (4, 'Diya',    'Bangalore', 'diya@test.com'),
    (5, 'Eshan',   'Ahmedabad', 'eshan@test.com');

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

ANALYZE TABLE customers, orders;

SELECT
    (SELECT COUNT(*) FROM customers) AS customers_total,
    (SELECT COUNT(*) FROM orders)    AS orders_total,
    (SELECT COUNT(DISTINCT city) FROM customers) AS distinct_cities;
