package com.payment.catalog.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * 库存聚合（ADR-0053 / 013）：以 SKU 为粒度管理库存。
 *
 * <p>核心不变量：{@code total = available + reserved + sold}（且各项非负）。
 * <ul>
 *   <li>available：当前可售（未被任何订单占用）。</li>
 *   <li>reserved：已被订单预占（下单时占用），尚未支付确认。</li>
 *   <li>sold：已支付确认扣减（reserved → sold）。</li>
 * </ul>
 * 状态迁移只在聚合内发生，仓储仅负责持久化（乐观锁防并发覆盖）。
 */
public class Stock {

    private Long id;
    private Integer version;
    private final Long skuId;
    private long total;
    private long available;
    private long reserved;
    private long sold;

    public Stock(Long skuId, long total) {
        this.skuId = skuId;
        this.total = total;
        this.available = total;
        this.reserved = 0L;
        this.sold = 0L;
        assertInvariant();
    }

    /** 持久化重建：还原快照与历史库存分布，绕过创建期初始化（不改变业务规则）。 */
    public static Stock rehydrate(Long id, Long skuId, long total, long available, long reserved,
                                  long sold, Integer version) {
        Stock s = new Stock(skuId, total);
        s.id = id;
        s.version = version;
        s.available = available;
        s.reserved = reserved;
        s.sold = sold;
        s.assertInvariant();
        return s;
    }

    /** 下单预占：available 减少、reserved 增加；库存不足抛 CONFLICT。 */
    public void reserve(long qty) {
        if (qty <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "reserve qty must be > 0");
        }
        if (available < qty) {
            throw BizException.of(ErrorCodes.CONFLICT,
                    "insufficient stock sku=" + skuId + " available=" + available + " requested=" + qty);
        }
        available -= qty;
        reserved += qty;
        assertInvariant();
    }

    /** 支付成功确认扣减：reserved 减少、sold 增加（幂等于 deductId）。 */
    public void confirm(long qty) {
        if (qty <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "confirm qty must be > 0");
        }
        if (reserved < qty) {
            throw BizException.of(ErrorCodes.CONFLICT,
                    "cannot confirm more than reserved sku=" + skuId + " reserved=" + reserved + " qty=" + qty);
        }
        reserved -= qty;
        sold += qty;
        assertInvariant();
    }

    /** 支付失败/超时释放：reserved 减少、available 回补（幂等）。 */
    public void release(long qty) {
        if (qty <= 0) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "release qty must be > 0");
        }
        if (reserved < qty) {
            throw BizException.of(ErrorCodes.CONFLICT,
                    "cannot release more than reserved sku=" + skuId + " reserved=" + reserved + " qty=" + qty);
        }
        reserved -= qty;
        available += qty;
        assertInvariant();
    }

    /** 不变量守卫：总量守恒 + 各项非负。 */
    private void assertInvariant() {
        if (total != available + reserved + sold) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "stock invariant violated sku=" + skuId + " total=" + total
                            + " avail=" + available + " reserved=" + reserved + " sold=" + sold);
        }
        if (available < 0 || reserved < 0 || sold < 0) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "stock negative sku=" + skuId);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getSkuId() {
        return skuId;
    }

    public long getTotal() {
        return total;
    }

    public long getAvailable() {
        return available;
    }

    public long getReserved() {
        return reserved;
    }

    public long getSold() {
        return sold;
    }
}
