package com.payment.catalog.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * 商品聚合根：商品身份、类型与生命周期状态。
 * 状态转换只通过领域方法进行，非法转换抛出 {@code STATE_TRANSITION_VIOLATION}。
 */
public class Product {

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String productCode;
    private final String name;
    private final String type;
    private ProductStatus status;

    public Product(String productCode, String name, String type) {
        this.productCode = productCode;
        this.name = name;
        this.type = type;
        this.status = ProductStatus.DRAFT;
    }

    /**
     * 持久化重建：用既有快照与历史状态还原商品聚合，绕过创建期状态机（不改变业务规则）。
     */
    public static Product rehydrate(Long id, String productCode, String name, String type,
                                    ProductStatus status, Integer version) {
        Product product = new Product(productCode, name, type);
        product.id = id;
        product.status = status;
        product.version = version;
        return product;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public ProductStatus getStatus() {
        return status;
    }

    /** DRAFT → LISTED。 */
    public void list() {
        requireStatus(ProductStatus.DRAFT, "list");
        this.status = ProductStatus.LISTED;
    }

    /** LISTED → UNLISTED。 */
    public void unlist() {
        requireStatus(ProductStatus.LISTED, "unlist");
        this.status = ProductStatus.UNLISTED;
    }

    /** UNLISTED → ARCHIVED。 */
    public void archive() {
        requireStatus(ProductStatus.UNLISTED, "archive");
        this.status = ProductStatus.ARCHIVED;
    }

    private void requireStatus(ProductStatus expected, String operation) {
        if (this.status != expected) {
            throw BizException.of(
                    ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "product " + operation + " requires " + expected + " status, current=" + this.status);
        }
    }
}
