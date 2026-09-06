DROP TABLE IF EXISTS transaction_refunds;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS transactions;

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    payment_no VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    total_minor BIGINT NOT NULL,
    paid_minor BIGINT NOT NULL DEFAULT 0,
    refunded_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_item_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    sku_id VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    price_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_order_items_order_item_no UNIQUE (order_item_no)
);

CREATE TABLE transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_no VARCHAR(32) NULL,
    refunded_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE transaction_refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(32) NOT NULL,
    payment_refund_no VARCHAR(32) NULL,
    transaction_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    payment_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_transaction_refunds_refund_no UNIQUE (refund_no),
    CONSTRAINT uk_transaction_refunds_idempotency_key UNIQUE (idempotency_key)
);
