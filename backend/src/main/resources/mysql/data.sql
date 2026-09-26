USE querysense;

INSERT IGNORE INTO categories (id, name) VALUES
    (1, 'Electronics'), (2, 'Books'), (3, 'Clothing'),
    (4, 'Home'), (5, 'Sports'), (6, 'Grocery');

INSERT IGNORE INTO suppliers (id, name, contact_email) VALUES
    (1, 'Apex Traders',     'sales@apex.test'),
    (2, 'Bharat Wholesale', 'orders@bharat.test'),
    (3, 'Coastal Imports',  'hello@coastal.test'),
    (4, 'Deccan Supply',    'contact@deccan.test'),
    (5, 'Everest Goods',    'team@everest.test'),
    (6, 'Fortune Distrib',  'info@fortune.test'),
    (7, 'Ganga Merchants',  'desk@ganga.test'),
    (8, 'Horizon Retail',   'buy@horizon.test');

INSERT IGNORE INTO departments (id, name) VALUES
    (1, 'Sales'), (2, 'Support'), (3, 'Warehouse'), (4, 'Finance'), (5, 'Engineering');

INSERT IGNORE INTO customers (id, name, email, city, phone) VALUES
    (1, 'Alice',   'alice@test.com',   'Mumbai',    '9800000001'),
    (2, 'Bob',     'bob@test.com',     'Pune',      '9800000002'),
    (3, 'Charlie', 'charlie@test.com', 'Delhi',     '9800000003'),
    (4, 'Diya',    'diya@test.com',    'Bangalore', '9800000004'),
    (5, 'Eshan',   'eshan@test.com',   'Ahmedabad', '9800000005');

INSERT IGNORE INTO customers (id, name, email, city, phone)
WITH RECURSIVE seq(n) AS (
    SELECT 6
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 303
)
SELECT
    n,
    CONCAT('Customer ', n),
    CONCAT('customer', n, '@test.com'),
    ELT(1 + (n % 5), 'Mumbai', 'Pune', 'Delhi', 'Bangalore', 'Ahmedabad'),
    CONCAT('98', LPAD(n, 8, '0'))
FROM seq;

INSERT IGNORE INTO employees (id, name, email, department_id, manager_id, hire_date) VALUES
    (1, 'Manager 1', 'manager1@querysense.test', 1, NULL, '2019-01-15'),
    (2, 'Manager 2', 'manager2@querysense.test', 2, NULL, '2019-06-01'),
    (3, 'Manager 3', 'manager3@querysense.test', 3, NULL, '2020-03-10'),
    (4, 'Manager 4', 'manager4@querysense.test', 4, NULL, '2020-11-20');

INSERT IGNORE INTO employees (id, name, email, department_id, manager_id, hire_date)
WITH RECURSIVE seq(n) AS (
    SELECT 5
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 30
)
SELECT
    n,
    CONCAT('Employee ', n),
    CONCAT('employee', n, '@querysense.test'),
    1 + (n % 5),
    1 + (n % 4),
    DATE_SUB(DATE '2026-09-01', INTERVAL (n * 47) DAY)
FROM seq;

INSERT IGNORE INTO products (id, name, price, category_id, supplier_id)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 60
)
SELECT
    n,
    CONCAT('Product ', n),
    ROUND(99 + ((n * 131) % 9900) / 10, 2),
    1 + (n % 6),
    1 + (n % 8)
FROM seq;

INSERT IGNORE INTO orders (id, customer_id, employee_id, order_date, status)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 900
)
SELECT
    n,
    1 + (n % 303),
    1 + (n % 30),
    DATE_SUB(DATE '2026-09-09', INTERVAL (n % 365) DAY),
    ELT(1 + (n % 4), 'PENDING', 'SHIPPED', 'DELIVERED', 'CANCELLED')
FROM seq;

INSERT IGNORE INTO order_items (id, order_id, product_id, quantity, unit_price)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 900
)
SELECT
    (seq.n - 1) * 2 + line.k,
    seq.n,
    1 + ((seq.n * 7 + line.k * 13) % 60),
    1 + ((seq.n + line.k) % 5),
    ROUND(99 + (((seq.n * 7 + line.k * 13) % 60 + 1) * 131 % 9900) / 10, 2)
FROM seq
CROSS JOIN (SELECT 1 AS k UNION ALL SELECT 2) AS line;

INSERT IGNORE INTO payments (id, order_id, amount, payment_date, method)
SELECT
    o.id,
    o.id,
    SUM(oi.quantity * oi.unit_price),
    DATE_ADD(o.order_date, INTERVAL (o.id % 3) DAY),
    ELT(1 + (o.id % 4), 'CARD', 'UPI', 'NETBANKING', 'COD')
FROM orders o
JOIN order_items oi ON oi.order_id = o.id
WHERE o.status <> 'CANCELLED'
GROUP BY o.id, o.order_date;

ANALYZE TABLE categories, suppliers, departments, customers, employees,
              products, orders, order_items, payments;

SELECT
    (SELECT COUNT(*) FROM customers)   AS customers_total,
    (SELECT COUNT(*) FROM employees)   AS employees_total,
    (SELECT COUNT(*) FROM products)    AS products_total,
    (SELECT COUNT(*) FROM orders)      AS orders_total,
    (SELECT COUNT(*) FROM order_items) AS order_items_total,
    (SELECT COUNT(*) FROM payments)    AS payments_total;
