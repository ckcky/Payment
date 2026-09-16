package com.payment.payment.limit.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限量配置（spec 027 / FR-021~023，ADR-0071 D13）：绑定 {@code payment.limit.*}。
 *
 * <pre>{@code
 * payment:
 *   limit:
 *     enabled: true
 *     reserve-ttl: 900s
 *     redis:
 *       key-prefix: "limit:pending:"
 * }</pre>
 *
 * <p><b>{@code enabled=false} 是一键回退开关</b>（FR-024）：关掉后建单路径完全跳过限额，
 * 与今天的行为逐字节一致——这是「限额误伤既有链路」的第一道保险。</p>
 *
 * <p><b>{@code reserve-ttl} 的下界是 105s</b>（D11）：= 支付侧最大自动收敛窗口
 * {@code payment.reliability.timeout}(30s) + {@code query-max-attempts × query-interval-ms}
 * (5×15s)。低于它就会释放一笔正在被主动查询收敛的支付。默认 900s，与
 * {@code order.timeout.ttl-seconds} 对齐（订单超时已被取消并释放库存，再占额度无意义）。</p>
 */
@ConfigurationProperties(prefix = "payment.limit")
public class LimitProperties {

    /** 灰度开关：false = 完全不参与建单路径（FR-024）。 */
    private boolean enabled = true;

    /**
     * 在途占用 TTL（D11/D13）。写入 Redis key 的过期时间，超过即视为可释放。
     *
     * <p>MUST &ge; 105s（见类注释）。配置低于下界时启动期 fail-fast——静默接受一个会
     * 「释放正在收敛的支付」的值，是那种要等到线上出现莫名额度缺失才会被发现的假绿。</p>
     */
    private Duration reserveTtl = Duration.ofSeconds(900);

    private Redis redis = new Redis();

    /** Redis 过期索引配置（D13：仅作 TTL 标记，不参与计数）。 */
    public static class Redis {
        /** key 前缀；最终 key 形如 {@code limit:pending:PM123...}。 */
        private String keyPrefix = "limit:pending:";

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getReserveTtl() {
        return reserveTtl;
    }

    public void setReserveTtl(Duration reserveTtl) {
        this.reserveTtl = reserveTtl;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis == null ? new Redis() : redis;
    }

    /** TTL 下界（D11）：小于它就会释放正在被主动查询收敛的支付。 */
    public static final Duration MIN_RESERVE_TTL = Duration.ofSeconds(105);

    /**
     * 启动强校验（对齐 ADR-0049 第 2 条「配错了必须响」）。
     *
     * <p>只校验「值本身是否自洽」，不校验「是否与 order.timeout.ttl-seconds 一致」——
     * 那需要跨服务读配置，属 runbook 的人工一致性要求（FR-041）。</p>
     */
    public void validate() {
        if (reserveTtl == null) {
            throw new IllegalStateException("payment.limit.reserve-ttl is required (e.g. 900s)");
        }
        if (reserveTtl.compareTo(MIN_RESERVE_TTL) < 0) {
            throw new IllegalStateException("payment.limit.reserve-ttl=" + reserveTtl
                    + " is below the safe lower bound " + MIN_RESERVE_TTL
                    + " (payment.reliability.timeout 30s + query-max-attempts 5 x query-interval-ms 15s"
                    + " = 105s): a shorter TTL would release payments that are still being"
                    + " actively queried to convergence");
        }
        if (redis == null || redis.getKeyPrefix() == null || redis.getKeyPrefix().isBlank()) {
            throw new IllegalStateException("payment.limit.redis.key-prefix is required"
                    + " (e.g. \"limit:pending:\")");
        }
    }
}
