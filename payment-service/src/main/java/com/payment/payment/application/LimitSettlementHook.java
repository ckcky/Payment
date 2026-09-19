package com.payment.payment.application;

import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.limit.application.LimitSettlementService;
import com.payment.payment.limit.domain.LimitExceededException;
import com.payment.payment.limit.infra.LimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 支付终态 → 额度结算的钩子（spec 027 / FR-013~014，ADR-0071 D4）。
 *
 * <p><b>为什么单独一层钩子而不让 {@code PaymentResultProcessor} 直连限额子域</b>：
 * ① 失败语义不同——本钩子 MUST 吞掉一切异常（结算失败不回滚支付事实，ADR-0009 哲学），
 * 而记账网关的异常由调用点处理；把「吞异常」这个决定隔离在一处，避免将来有人
 * 「顺手」把异常放出去导致支付成功被回滚。② 让既有测试能注入 {@link #noop()} 保持零改动（SC-002）。</p>
 *
 * <p><b>映射表</b>（plan §3.2）：</p>
 * <table>
 *   <tr><th>payment 终态</th><th>操作</th><th>金额变动</th></tr>
 *   <tr><td>{@code SUCCEEDED}</td><td>CONFIRM</td><td>{@code used += a}、{@code pending -= a}</td></tr>
 *   <tr><td>{@code FAILED} / {@code CLOSED}</td><td>RELEASE</td><td>{@code pending -= a}</td></tr>
 *   <tr><td>{@code UNKNOWN}</td><td>不结算</td><td>{@code pending} 保持占用（INV-5）</td></tr>
 * </table>
 *
 * <p>金额一律取 {@code payment.getAmountMinor()}（INV-6），<b>不用</b>渠道回传的实付额。</p>
 */
@Component
public class LimitSettlementHook {

    private static final Logger log = LoggerFactory.getLogger(LimitSettlementHook.class);

    private final LimitSettlementService settlementService;
    private final LimitProperties properties;

    public LimitSettlementHook(@Autowired(required = false) LimitSettlementService settlementService,
                               @Autowired(required = false) LimitProperties properties) {
        this.settlementService = settlementService;
        this.properties = properties;
    }

    /**
     * 按支付终态结算额度。<b>本方法绝不抛出</b>——任何异常都只记 WARN，交由
     * {@code LimitCompensationScanner} 收敛（FR-016）。
     */
    public void settle(Payment payment) {
        if (settlementService == null || properties == null || !properties.isEnabled()) {
            return;
        }
        PaymentStatus status = payment.getStatus();
        try {
            switch (status) {
                case SUCCEEDED -> settlementService.confirm(payment.getUserId(), payment.getCurrencyCode(),
                        payment.getAmountMinor(), payment.getPaymentNo());
                case FAILED, CLOSED -> settlementService.release(payment.getUserId(),
                        payment.getCurrencyCode(), payment.getAmountMinor(), payment.getPaymentNo());
                case UNKNOWN -> {
                    // INV-5：UNKNOWN 不结算，pending 保持占用。
                    // 释放由 TTL 惰性回收（D13）或人工收敛负责，绝不在这里猜成败。
                }
                default -> {
                    // PENDING / PROCESSING 不是终态，changed=true 时不应出现（防御性忽略）
                }
            }
        } catch (LimitExceededException e) {
            // CONFIRM 不做限额判定，理论上不会抛；真抛了也不能回滚支付事实
            log.warn("额度结算抛出超限异常（不阻断支付事实，交由补偿扫描收敛）paymentNo={} reason={}",
                    payment.getPaymentNo(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("额度结算失败（不回滚支付事实，交由补偿扫描收敛）paymentNo={} status={} reason={}",
                    payment.getPaymentNo(), status, e.getMessage());
        }
    }

    /** 空实现（测试 / 限额未接入）：恒不结算。 */
    public static LimitSettlementHook noop() {
        return new LimitSettlementHook(null, null);
    }
}
