package com.payment.catalog.application.seckill;

/**
 * 秒杀预扣结果（014）。
 * <ul>
 *   <li>{@code allowed=true, bypassed=false}：配额充足，已原子扣减，{@code remaining} 为扣减后剩余。</li>
 *   <li>{@code allowed=true, bypassed=true}：该 SKU 未播种秒杀配额（非秒杀品），直接放行。</li>
 *   <li>{@code allowed=false}：配额不足或 Redis 不可用（fail-closed 保护库存），拒绝。</li>
 * </ul>
 */
public record SeckillResult(boolean allowed, long remaining, boolean bypassed) {

    public static SeckillResult allowed(long remaining) {
        return new SeckillResult(true, remaining, false);
    }

    public static SeckillResult bypass() {
        return new SeckillResult(true, -2, true);
    }

    public static SeckillResult deny() {
        return new SeckillResult(false, -1, false);
    }
}
