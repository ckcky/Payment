package com.payment.order.infra.persistence.order;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 订单明细 + 价格快照持久化实体（PO）。明细创建后不可变，仅随订单创建插入。
 */
@TableName("order_items")
public class OrderItemEntity extends BaseEntity {

    private String orderNo;
    private String skuId;
    private String skuCode;
    private String name;
    private Integer quantity;
    /** 最小货币单位（BIGINT），禁止浮点。 */
    private Long priceMinor;
    private String currencyCode;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
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
}
