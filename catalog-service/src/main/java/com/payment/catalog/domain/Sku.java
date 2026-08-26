package com.payment.catalog.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * SKU：商品引用、可销售属性、价格引用（最小货币单位，禁止浮点）与交付定义。
 * 只有 {@link SkuStatus#SELLABLE} 的 SKU 才允许下单。
 */
public class Sku {

    private Long id;
    private final String skuCode;
    private final Long productId;
    private final String name;
    private final long priceMinor;
    private final String currencyCode;
    private final String deliveryDefinition;
    private SkuStatus status;

    public Sku(String skuCode, Long productId, String name, long priceMinor, String currencyCode,
               String deliveryDefinition) {
        this.skuCode = skuCode;
        this.productId = productId;
        this.name = name;
        this.priceMinor = priceMinor;
        this.currencyCode = currencyCode;
        this.deliveryDefinition = deliveryDefinition;
        this.status = SkuStatus.DRAFT;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public Long getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getDeliveryDefinition() {
        return deliveryDefinition;
    }

    public SkuStatus getStatus() {
        return status;
    }

    /** DRAFT → SELLABLE。 */
    public void activate() {
        requireStatus(SkuStatus.DRAFT, "activate");
        this.status = SkuStatus.SELLABLE;
    }

    /** SELLABLE → SUSPENDED。 */
    public void suspend() {
        requireStatus(SkuStatus.SELLABLE, "suspend");
        this.status = SkuStatus.SUSPENDED;
    }

    /** SELLABLE 或 SUSPENDED → DISCONTINUED。 */
    public void discontinue() {
        if (this.status != SkuStatus.SELLABLE && this.status != SkuStatus.SUSPENDED) {
            throw BizException.of(
                    ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "sku discontinue requires SELLABLE or SUSPENDED status, current=" + this.status);
        }
        this.status = SkuStatus.DISCONTINUED;
    }

    /** 只有 SELLABLE 的 SKU 才允许加入新订单。 */
    public boolean isSellable() {
        return this.status == SkuStatus.SELLABLE;
    }

    private void requireStatus(SkuStatus expected, String operation) {
        if (this.status != expected) {
            throw BizException.of(
                    ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "sku " + operation + " requires " + expected + " status, current=" + this.status);
        }
    }
}
