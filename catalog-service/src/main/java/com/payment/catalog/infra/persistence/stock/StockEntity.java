package com.payment.catalog.infra.persistence.stock;

import com.baomidou.mybatisplus.annotation.TableName;
import com.payment.common.mybatis.BaseEntity;

/**
 * 库存持久化实体（PO）：承载 stock 表列，库存状态机逻辑在 {@code domain.Stock}。
 */
@TableName("stock")
public class StockEntity extends BaseEntity {

    private Long skuId;
    private Long total;
    private Long available;
    private Long reserved;
    private Long sold;

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getAvailable() {
        return available;
    }

    public void setAvailable(Long available) {
        this.available = available;
    }

    public Long getReserved() {
        return reserved;
    }

    public void setReserved(Long reserved) {
        this.reserved = reserved;
    }

    public Long getSold() {
        return sold;
    }

    public void setSold(Long sold) {
        this.sold = sold;
    }
}
