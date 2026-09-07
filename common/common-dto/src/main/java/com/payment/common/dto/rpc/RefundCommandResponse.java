package com.payment.common.dto.rpc;

/**
 * 退款命令响应（order-service → payment-service，Feature 016 立项 / spec 019 / ADR-0067 升级）。
 *
 * <p>{@code refundNo} 为支付层退款执行单号（<b>PMRF</b>+雪花，spec 019 起新单不再用 RF 前缀，
 * 存量 RF 保留）；{@code status} 为退款单当前态（PROCESSING / SUCCEEDED / FAILED / UNKNOWN /
 * REJECTED）——异步模式下受理成功即返回 PROCESSING，终态经退款回调 +
 * {@link RefundResultNotification} 通知 order 收口。</p>
 */
public record RefundCommandResponse(String refundNo, String status) {
}
