package com.payment.refund.application;

import com.payment.payment.application.channel.ChannelResult;

/**
 * refund 域 → payment 域的退款尝试收敛端口（fix：payment_attempts 的 REFUND 行状态同步）：
 * 退款结果在<b>任一收敛路径</b>（同步受理终态 / 异步渠道回调 / resolve 人工裁定）取得权威终态时，
 * 把 payment 域对应的 REFUND 尝试行（{@code payment_attempts.attempt_type=REFUND}）从
 * UNKNOWN/ACCEPTED/PENDING 收敛到 SUCCEEDED/FAILED——与退款单状态同源同路，不留双路径。
 *
 * <p>背景：019 之前尝试行只在渠道调用瞬间落一次状态，异步受理（UNKNOWN）后回调到达时
 * 无人更新，导致演示与对账视角的退款尝试永久停留在 UNKNOWN。</p>
 */
public interface RefundAttemptSettlementGateway {

    /**
     * 将该支付单的退款尝试收敛到终态。
     *
     * @param paymentNo        所属支付单号（尝试行的归属锚点）
     * @param channelReference 渠道退款流水号（优先精确匹配；resolve 路径可能为 null，回退最近一条未收敛尝试）
     * @param outcome          渠道权威结果（仅 SUCCESS/FAILURE 有效，UNKNOWN 不收敛）
     */
    void convergeToTerminal(String paymentNo, String channelReference, ChannelResult outcome);
}
