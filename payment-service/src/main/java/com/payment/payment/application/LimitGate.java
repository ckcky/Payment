package com.payment.payment.application;

import com.payment.payment.limit.application.LimitPendingRecycler;
import com.payment.payment.limit.application.LimitReserveService;
import com.payment.payment.limit.infra.LimitProperties;
import org.springframework.stereotype.Component;

/**
 * 建单前的限额闸门（spec 027 / FR-006、FR-010、INV-3）。
 *
 * <p><b>本类不含 {@code @Transactional}</b>：它的两个动作被<b>调用方的事务</b>包住
 * （{@link PaymentPersistence#insertPending} 的 {@code @Transactional} 方法体）。
 * 这样「惰性回收 + 预占 + 建支付单」三者同生共死——超限抛异常时整个建单事务回滚，
 * {@code payments} 表不落行。这是 SC-003「<b>未创建</b>而非创建了再拒」的实现基础。</p>
 *
 * <p><b>为什么不把闸门做成一个 @Transactional Bean 让调用方调用</b>：
 * 那样闸门自己会先提交（或至少与建单分属不同事务边界），中间失败就出现
 * 「预占成功但建单失败」——额度凭空被扣，且要靠补偿才能复原。事务边界画在
 * <b>建单方法</b>上，闸门作为普通组件被它调用，边界才是唯一且显式的。</p>
 *
 * <p><b>事务内只有 DB 往返</b>（无 RPC、无渠道调用）：渠道调用仍在
 * {@code createPaymentIntentWithRouting} 的事务外执行，P0-3 的「网络调用不进事务」
 * 设计不受影响。</p>
 */
@Component
public class LimitGate {

    private final LimitReserveService reserveService;
    private final LimitPendingRecycler recycler;
    private final LimitProperties properties;

    public LimitGate(LimitReserveService reserveService,
                     LimitPendingRecycler recycler,
                     LimitProperties properties) {
        this.reserveService = reserveService;
        this.recycler = recycler;
        this.properties = properties;
    }

    /**
     * 建单前的限额闸门：惰性回收 → 三周期原子预占。
     *
     * <p><b>调用方 MUST 在事务内调用</b>（{@link PaymentPersistence#insertPending} 内），
     * 否则超限时无法回滚建单。</p>
     *
     * @return {@code true} = 发生了预占；{@code false} = 开关关闭 / 无配置 / 三周期都不限
     */
    public boolean acquire(String userId, String currencyCode, long amountMinor, String paymentNo) {
        if (!properties.isEnabled()) {
            return false;
        }
        // 惰性回收（FR-038）必须在预占判定之前：把该用户已过期的在途先释放掉，
        // 否则「上次挂单 + 本次新单」会误判超限（用户明明还有额度）。
        //
        // 回收走 REQUIRES_NEW 短事务（LimitSettlementService#expire），故即使随后
        // 预占失败导致建单事务回滚，已回收的过期占用也不会被回滚——这是正确的：
        // 过期占用本就该被释放，与本次建单成败无关。
        recycler.recycle(userId);
        return reserveService.reserve(userId, currencyCode, amountMinor, paymentNo);
    }

    /** 当前是否启用限额（供演示 / 测试断言）。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 恒不放行的空实现（测试 / 限额未接入场景）。
     *
     * <p>与「限额关闭」的区别：关闭是「配置说了不做」，本实现是「压根没接」。
     * 两者在行为上一致（都不预占、不拦截），但空实现不持有任何依赖，
     * 让既有测试的构造签名保持零改动（SC-002）。</p>
     */
    public static LimitGate disabled() {
        return new LimitGate(null, null, null) {
            @Override
            public boolean acquire(String userId, String currencyCode, long amountMinor, String paymentNo) {
                return false;
            }

            @Override
            public boolean isEnabled() {
                return false;
            }
        };
    }
}
