package com.payment.payment.application;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;

/**
 * 把渠道结果应用到 <b>payment 聚合</b> 状态机（Feature 028 / FR-004，ADR-0072）。
 *
 * <p><b>本类只管 payment 侧</b>：渠道层负责 {@code attempt} 的收敛
 * （见 {@code ChannelAttemptRecorder#converge}），payment 层负责 {@code payment} 的状态迁移，
 * 两者由调用方在同一事务内协调（INV-5）。Feature 028 前，一个
 * {@code apply(Payment, PaymentAttempt, ChannelResult)} 方法同时推进两个聚合（spec §1.2 S2）
 * ——现在拆开，职责各归其层。</p>
 *
 * <p>返回 {@code true} 表示「支付发生了真正的状态迁移」，调用方据此触发一次履约 RPC；
 * 终态冲突/重复回调由状态机吸收（返回 {@code false}），保证「最多一次」业务效果。</p>
 */
final class PaymentResultApplier {

    private PaymentResultApplier() {
    }

    /**
     * 仅推进 payment 聚合（attempt 侧已由渠道层收敛，FR-004）。
     *
     * <p><b>调用顺序约束</b>：调用方 MUST 先收敛 attempt、再调用本方法（与拆分前一致），
     * 以保证乐观锁版本号与幂等重放断言不变（plan §B4 风险点）。</p>
     */
    static boolean applyPayment(Payment payment, ChannelResult result) {
        return switch (result.status()) {
            case SUCCESS -> payment.succeed();
            case FAILURE -> payment.fail(result.reason());
            case UNKNOWN -> payment.markUnknown(result.reason());
        };
    }

    static PaymentSucceededRequest toSucceededRequest(Payment payment) {
        return toSucceededRequest(payment, false);
    }

    /**
     * 带 late 标记的构造（spec 034 / T18，C-23）：CLOSED 上的迟到成功通知 order 追回时
     * 置 {@code late=true}，order 既有 ORDER_NOT_PAYABLE surplus 分支吸收。
     */
    static PaymentSucceededRequest toSucceededRequest(Payment payment, boolean late) {
        // payment 侧不持有订单明细，按约定传空 items（由 order 层富化，FR-005 / ADR-0066）
        return PaymentSucceededRequest.withoutItems(payment.getPaymentNo(), payment.getOrderNo(),
                payment.getTransactionId(), payment.getUserId(),
                payment.getAmountMinor(), payment.getCurrencyCode()).withLate(late);
    }

    /** 渠道结果 → 支付终态（调用方据此记录审计与指标）。 */
    static PaymentStatus terminalStatusOf(ChannelResult result) {
        return switch (result.status()) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
        };
    }
}
