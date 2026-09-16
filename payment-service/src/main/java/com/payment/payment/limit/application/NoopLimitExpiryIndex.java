package com.payment.payment.limit.application;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 空实现在途过期索引（spec 027 / INV-9.2，降级实现）。
 *
 * <p><b>用于两类场景</b>：</p>
 * <ol>
 *   <li><b>未配置 Redis</b>（H2 测试、无 Redis 的本地环境）：{@link #alive} 返回
 *       {@code null} → 调用方跳过回收 → 在途<b>保持占用</b>（保守）。这正是 SC-016
 *       「未配置 Redis：服务正常启动、建单不报错不拦截、在途保持占用」的实现。</li>
 *   <li><b>配置缺失时的兜底 Bean</b>：与 {@code RedisLimitExpiryIndex} 二选一。</li>
 * </ol>
 *
 * <p><b>为什么不是「返回空集」</b>：返回空集意味着「所有在途都已过期」，会让首次建单
 * 就把之前所有未结算的在途一次性释放——约束静默变松，与 INV-9 的方向性原则相反。</p>
 *
 * <p><b>代价是明确的</b>：无 Redis 时 TTL 语义不存在，{@code deferChannel} 的挂单会
 * 永久占用该用户额度，只能靠补偿扫描（只处理已终态的）或人工处置。这是「拿正确性换
 * 可用性」的自觉取舍，且只影响该用户自己（D13 自愈性论证）。</p>
 */
public class NoopLimitExpiryIndex implements LimitExpiryIndex {

    @Override
    public void mark(String paymentNo, long amountMinor, Duration ttl) {
        // 无索引：不做任何事
    }

    @Override
    public void clear(String paymentNo) {
        // 无索引：不做任何事
    }

    @Override
    public Set<String> alive(Collection<String> paymentNos) {
        // null = 无法判定 → 调用方跳过回收（保守占用，INV-9.2）
        return null;
    }

    /** 供测试与诊断用：本实现是否为可判定的（恒 false）。 */
    public boolean isAvailable() {
        return false;
    }

    /** 便捷工厂（配置缺失时的兜底 Bean）。 */
    public static NoopLimitExpiryIndex instance() {
        return new NoopLimitExpiryIndex();
    }

    /** 空集合语义的显式命名，避免调用方误用 {@link #alive}。 */
    public static Set<String> emptyAlive() {
        return new HashSet<>();
    }
}
