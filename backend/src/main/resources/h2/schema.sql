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
