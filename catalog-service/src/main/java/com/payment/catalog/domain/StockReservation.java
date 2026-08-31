package com.payment.catalog.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * 库存预占记录（聚合内值对象，由 {@code stock_reservation} 表持久化）。
 *
 * <p>幂等键为 {@link #reservationId}（订单维度，如 {@code order:1:sku:2}）。
 * 支付成功确认时携带 {@link #deductId}（支付单号），用于重复回调的幂等吸收。</p>
 */
public class StockReservation {

    private final String reservationId;
    private final Long skuId;
    private final long quantity;
    private ReservationStatus status;
    private String deductId;

    public StockReservation(String reservationId, Long skuId, long quantity, ReservationStatus status, String deductId) {
        this.reservationId = reservationId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.status = status;
        this.deductId = deductId;
    }

    /** 确认扣减（幂等）：已是 CONFIRMED 则吸收；RELEASED 不允许再确认。 */
    public void confirm(String deductId) {
        if (this.status == ReservationStatus.CONFIRMED) {
            return;
        }
        if (this.status == ReservationStatus.RELEASED) {
            throw BizException.of(ErrorCodes.CONFLICT, "cannot confirm released reservation: " + reservationId);
        }
        this.deductId = deductId;
        this.status = ReservationStatus.CONFIRMED;
    }

    /** 释放（幂等）：已是 RELEASED 则吸收；CONFIRMED 不允许回滚（退款另计）。 */
    public void release() {
        if (this.status == ReservationStatus.RELEASED) {
            return;
        }
        if (this.status == ReservationStatus.CONFIRMED) {
            throw BizException.of(ErrorCodes.CONFLICT, "cannot release confirmed reservation: " + reservationId);
        }
        this.status = ReservationStatus.RELEASED;
    }

    public String getReservationId() {
        return reservationId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public long getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getDeductId() {
        return deductId;
    }
}
