package com.payment.catalog.infra.persistence.sku;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * SKU 持久化实体（PO）：承载 SKU 表列，状态机逻辑在 {@code domain.Sku}。
 */
@TableName("skus")
public class SkuEntity extends BaseEntity {

    private String skuCode;
    private Long productId;
    private String name;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long priceMinor;
    private String currencyCode;
    private String deliveryDefinition;
    /** SKU 可售状态机枚举名。 */
    private String status;

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getPriceMinor() {
        return priceMinor;
    }

    public void setPriceMinor(Long priceMinor) {
        this.priceMinor = priceMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getDeliveryDefinition() {
        return deliveryDefinition;
    }

    public void setDeliveryDefinition(String deliveryDefinition) {
        this.deliveryDefinition = deliveryDefinition;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
