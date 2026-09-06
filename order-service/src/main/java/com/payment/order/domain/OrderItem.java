package com.payment.order.domain;

import java.util.Objects;

/**
 * 订单明细 + 价格快照（不可变）。价格与交付定义在创建订单时冻结，历史订单不追溯修改。
 */
public final class OrderItem {

    /** 业务单号（OI + 雪花，ADR-0066）：跨服务引用明细的身份键，数值主键不出边界（ADR-0063）。 */
    private final String orderItemNo;
    private final String skuId;
    private final String skuCode;
    private final String name;
    private final int quantity;
    private final long priceMinor;
    private final String currencyCode;

    public OrderItem(String orderItemNo, String skuId, String skuCode, String name, int quantity,
                     long priceMinor, String currencyCode) {
        this.orderItemNo = Objects.requireNonNull(orderItemNo, "orderItemNo");
        this.skuId = Objects.requireNonNull(skuId, "skuId");
        this.skuCode = Objects.requireNonNull(skuCode, "skuCode");
        this.name = Objects.requireNonNull(name, "name");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (priceMinor <= 0) {
            throw new IllegalArgumentException("priceMinor must be > 0");
        }
        this.quantity = quantity;
        this.priceMinor = priceMinor;
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
    }

    /** 明细小计（最小货币单位），溢出抛异常。 */
    public long subtotalMinor() {
        return Math.multiplyExact(priceMinor, quantity);
    }

    public String getOrderItemNo() {
        return orderItemNo;
    }

    public String getSkuId() {
        return skuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
