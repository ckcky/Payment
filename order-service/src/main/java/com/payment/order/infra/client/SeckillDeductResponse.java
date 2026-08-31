package com.payment.order.infra.client;

/**
 * catalog-service 秒杀预扣端点的响应镜像（订单服务侧）。
 * {@code remaining} 为扣减后剩余；{@code bypassed=true} 表示该 SKU 未播种秒杀配额（普通品放行）。
 */
public record SeckillDeductResponse(long remaining, boolean bypassed) {
}
