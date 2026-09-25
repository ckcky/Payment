package com.payment.payment.application;

/**
 * 渠道支付回调的<b>处理结论</b>（Payment → 渠道网关，spec 037 / FR-010）。
 *
 * <p>渠道网关把「渠道说这笔钱怎么样了」翻译出来交给 Payment（{@link PaymentNotifyPort}），
 * Payment 做完业务校验与终态收敛后，用本结论告诉网关<b>该对渠道回什么</b>——
 * 网关不认识任何业务规则，只负责把结论转成渠道协议要求的应答体。</p>
 *
 * <h3>三档语义（与既有控制器的三分支逐字对应，NFR-2）</h3>
 * <ul>
 *   <li>{@link Status#ACCEPTED}：校验通过且已收敛 ⇒ 回<b>渠道成功应答体</b>
 *       （如支付宝要求精确的 {@code success}）；</li>
 *   <li>{@link Status#REJECTED}：业务校验判定「这条通知不可信」（金额/币种/串号不符）
 *       ⇒ 回<b>非成功应答</b>，让渠道按其策略重推——告诉渠道「已收到」会让这条差异
 *       永远沉默，而资金差异正是藏在那里；</li>
 *   <li>{@link Status#ERROR}：处理过程中出了未预期的问题 ⇒ 回 {@code processing error}
 *       （不泄漏内部细节，FR-245），同样触发渠道重推。</li>
 * </ul>
 *
 * <p><b>为什么 REJECTED 与 ERROR 要分开</b>：前者是「我确定这条通知不对」（明确结论，
 * 排障时可直接对账），后者是「我不知道发生了什么」（需要看日志/指标）。把两者压成
 * 一个「失败」会让事故定位从「查这条通知哪里不对」退化成「全链路捞日志」。</p>
 *
 * @param status 结论档位
 * @param detail 结论细节（拒绝原因 / 异常摘要；{@link Status#ACCEPTED} 时为 {@code null}）
 */
public record PayNotifyOutcome(Status status, String detail) {

    /** 处理结论档位。 */
    public enum Status {
        /** 校验通过且已收敛 ⇒ 回渠道成功应答体。 */
        ACCEPTED,
        /** 业务校验判定通知不可信 ⇒ 回非成功应答触发渠道重推。 */
        REJECTED,
        /** 处理异常 ⇒ 回 {@code processing error}，同样触发渠道重推。 */
        ERROR
    }

    public PayNotifyOutcome {
        if (status == null) {
            throw new IllegalArgumentException("pay notify outcome must carry a status");
        }
    }

    /** 已收敛。 */
    public static PayNotifyOutcome accepted() {
        return new PayNotifyOutcome(Status.ACCEPTED, null);
    }

    /** 业务校验拒绝（{@code reason} 为可读的拒绝原因，不含内部标识，FR-245）。 */
    public static PayNotifyOutcome rejected(String reason) {
        return new PayNotifyOutcome(Status.REJECTED, reason);
    }

    /** 处理异常（{@code detail} 仅用于日志，不回传渠道）。 */
    public static PayNotifyOutcome error(String detail) {
        return new PayNotifyOutcome(Status.ERROR, detail);
    }

    /** 是否已收敛（网关据此决定回成功应答体还是非成功应答）。 */
    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }
}
