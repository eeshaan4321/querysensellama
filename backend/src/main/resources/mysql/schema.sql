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
