INSERT INTO categories (id, name) VALUES
    (1, 'Electronics'), (2, 'Books'), (3, 'Clothing'), (4, 'Home'), (5, 'Sports');

INSERT INTO suppliers (id, name, contact_email) VALUES
    (1, 'Apex Traders',     'sales@apex.test'),
    (2, 'Bharat Wholesale', 'orders@bharat.test'),
    (3, 'Coastal Imports',  'hello@coastal.test'),
    (4, 'Deccan Supply',    'contact@deccan.test');

INSERT INTO departments (id, name) VALUES
    (1, 'Sales'), (2, 'Support'), (3, 'Warehouse');

INSERT INTO employees (id, name, email, department_id, manager_id, hire_date) VALUES
    (1, 'Anita',  'anita@querysense.test',  1, NULL, '2019-01-15'),
    (2, 'Barun',  'barun@querysense.test',  2, NULL, '2019-06-01'),
    (3, 'Chitra', 'chitra@querysense.test', 1, 1,    '2021-02-11'),
    (4, 'Dev',    'dev@querysense.test',    1, 1,    '2022-07-04'),
    (5, 'Ela',    'ela@querysense.test',    2, 2,    '2023-03-19'),
    (6, 'Faisal', 'faisal@querysense.test', 3, 2,    '2024-10-02'),
    (7, 'Gita',   'gita@querysense.test',   3, 6,    '2025-05-27'),
    (8, 'Hemant', 'hemant@querysense.test', 1, 3,    '2026-01-08');

INSERT INTO customers (id, name, city, email) VALUES
    (1, 'Alice',   'Mumbai',    'alice@test.com'),
    (2, 'Bob',     'Pune',      'bob@test.com'),
    (3, 'Charlie', 'Delhi',     'charlie@test.com'),
    (4, 'Diya',    'Bangalore', 'diya@test.com'),
    (5, 'Eshan',   'Ahmedabad', 'eshan@test.com');

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

UPDATE customers SET phone = CONCAT('98', LPAD(CAST(id AS VARCHAR), 8, '0'));

INSERT INTO products (id, name, price, category_id, supplier_id)
SELECT X, CONCAT('Product ', X), ROUND(99 + MOD(X * 131, 9900) / 10.0, 2), 1 + MOD(X, 5), 1 + MOD(X, 4)
FROM SYSTEM_RANGE(1, 20);

INSERT INTO orders (id, customer_id, employee_id, order_date, status) VALUES
    (1 , 1 , 2, '2026-08-01', 'shipped'),
    (2 , 1 , 3, '2026-08-04', 'shipped'),
    (3 , 1 , 4, '2026-08-11', 'pending'),
    (4 , 1 , 5, '2026-08-19', 'shipped'),
    (5 , 1 , 6, '2026-09-02', 'cancelled'),
    (6 , 6 , 7, '2026-07-22', 'shipped'),
    (7 , 6 , 8, '2026-08-03', 'shipped'),
    (8 , 6 , 1, '2026-08-14', 'pending'),
    (9 , 6 , 2, '2026-08-28', 'shipped'),
    (10, 6 , 3, '2026-09-05', 'pending'),
    (11, 11, 4, '2026-08-06', 'shipped'),
    (12, 11, 5, '2026-08-17', 'cancelled'),
    (13, 11, 6, '2026-08-25', 'shipped'),
    (14, 11, 7, '2026-09-01', 'pending'),
    (15, 16, 8, '2026-08-09', 'shipped'),
    (16, 16, 1, '2026-08-21', 'pending'),
    (17, 16, 2, '2026-09-03', 'shipped'),
    (18, 21, 3, '2026-08-13', 'shipped'),
    (19, 21, 4, '2026-08-30', 'cancelled'),
    (20, 2 , 5, '2026-08-02', 'shipped'),
    (21, 2 , 6, '2026-08-15', 'pending'),
    (22, 2 , 7, '2026-08-27', 'shipped'),
    (23, 2 , 8, '2026-09-04', 'shipped'),
    (24, 3 , 1, '2026-08-08', 'shipped'),
    (25, 3 , 2, '2026-08-20', 'pending'),
    (26, 4 , 3, '2026-08-12', 'shipped'),
    (27, 5 , 4, '2026-08-24', 'cancelled'),
    (28, 7 , 5, '2026-08-05', 'shipped'),
    (29, 7 , 6, '2026-08-18', 'shipped'),
    (30, 8 , 7, '2026-08-29', 'pending'),
    (31, 9 , 8, '2026-09-06', 'shipped'),
    (32, 10, 1, '2026-08-10', 'shipped'),
    (33, 12, 2, '2026-08-23', 'pending'),
    (34, 13, 3, '2026-08-31', 'shipped'),
    (35, 14, 4, '2026-09-07', 'cancelled'),
    (36, 17, 5, '2026-08-16', 'shipped'),
    (37, 18, 6, '2026-08-26', 'shipped'),
    (38, 19, 7, '2026-09-08', 'pending'),
    (39, 22, 8, '2026-08-07', 'shipped'),
    (40, 23, 1, '2026-08-22', 'shipped');

INSERT INTO order_items (id, order_id, product_id, quantity, unit_price)
SELECT X, 1 + MOD(X - 1, 40), 1 + MOD(X * 7, 20), 1 + MOD(X, 4), ROUND(99 + MOD((1 + MOD(X * 7, 20)) * 131, 9900) / 10.0, 2)
FROM SYSTEM_RANGE(1, 80);

INSERT INTO payments (id, order_id, amount, payment_date, method)
SELECT o.id, o.id, SUM(oi.quantity * oi.unit_price), DATEADD('DAY', MOD(o.id, 3), o.order_date),
       CASE MOD(o.id, 4) WHEN 0 THEN 'CARD' WHEN 1 THEN 'UPI' WHEN 2 THEN 'NETBANKING' ELSE 'COD' END
FROM orders o
JOIN order_items oi ON oi.order_id = o.id
WHERE o.status <> 'cancelled'
GROUP BY o.id, o.order_date;
