DROP TABLE IF EXISTS entitlements;

CREATE TABLE entitlements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    source_fulfillment_id VARCHAR(64) NOT NULL,
    grant_ref VARCHAR(64),
    available_quantity INT NOT NULL,
    scope VARCHAR(64) NOT NULL,
    expiry_at TIMESTAMP,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_entitlements_source_fulfillment_id UNIQUE (source_fulfillment_id)
);
