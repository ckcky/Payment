package com.payment.order.application;

/**
 * 秒杀预扣结果（订单服务侧）：{@code allowed=true} 表示配额充足（或该 SKU 未播种秒杀配额、直接放行），
 * 订单可继续；{@code allowed=false} 表示秒杀配额不足，应拒绝下单（CONFLICT）。
 */
public record SeckillResult(boolean allowed, long remaining) {
}
