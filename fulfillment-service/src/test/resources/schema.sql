DROP TABLE IF EXISTS fulfillments;

CREATE TABLE fulfillments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_payment_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    order_item_id VARCHAR(64) NOT NULL,
    delivery_content VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_fulfillments_source_payment_item UNIQUE (source_payment_no, order_item_id)
);
