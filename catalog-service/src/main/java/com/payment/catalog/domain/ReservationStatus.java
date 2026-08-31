package com.payment.catalog.domain;

/**
 * 预占记录状态：PENDING（已预占待支付）→ CONFIRMED（支付成功已扣减）/ RELEASED（失败/超时已释放）。
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    RELEASED
}
