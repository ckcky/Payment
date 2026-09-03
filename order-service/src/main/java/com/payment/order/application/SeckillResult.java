package com.payment.order.application;

/**
 * 秒杀预扣结果（订单服务侧）：{@code allowed=true} 表示配额充足（或该 SKU 未播种秒杀配额、直接放行），
 * 订单可继续；{@code allowed=false} 表示秒杀配额不足，应拒绝下单（CONFLICT）。
 *
 * <p>{@code bypassed=true} 表示该 SKU 未播种秒杀配额、Redis 配额未被触碰：
 * 失败回滚时不得对其调用 rollbackSeckill（INCREMENT 会凭空造出配额键，导致后续
 * 正常下单被误判"秒杀库存不足"，2026-09-04 压测踩坑）。</p>
 */
public record SeckillResult(boolean allowed, long remaining, boolean bypassed) {
}
