DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS employees;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS departments;
DROP TABLE IF EXISTS suppliers;
DROP TABLE IF EXISTS categories;

CREATE TABLE categories (
    id   INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE suppliers (
    id            INT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    contact_email VARCHAR(150)
);

CREATE TABLE departments (
    id   INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE customers (
    id    INT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    email VARCHAR(150),
    city  VARCHAR(100),
    phone VARCHAR(20)
);

CREATE TABLE employees (
    id            INT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(150),
    department_id INT,
    manager_id    INT,
    hire_date     DATE,
    CONSTRAINT fk_employees_department FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT fk_employees_manager FOREIGN KEY (manager_id) REFERENCES employees(id)
);

CREATE TABLE products (
    id          INT PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    price       DECIMAL(10,2),
    category_id INT,
    supplier_id INT,
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT fk_products_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
);

CREATE TABLE orders (
    id          INT PRIMARY KEY,
    customer_id INT,
    employee_id INT,
    order_date  DATE,
    status      VARCHAR(30),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_orders_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
);

CREATE TABLE order_items (
    id         INT PRIMARY KEY,
    order_id   INT NOT NULL,
    product_id INT NOT NULL,
    quantity   INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE TABLE payments (
    id           INT PRIMARY KEY,
    order_id     INT NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    payment_date DATE,
    method       VARCHAR(30),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders(id)
);
