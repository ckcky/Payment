package com.payment.channelgateway.application;

import com.payment.common.dto.channel.PayCredential;

import java.util.Optional;

/**
 * 扣款派发策略（spec 041 / FR-021）：渠道网关在<b>触达渠道实现之前</b>的裁决点。
 *
 * <h3>为什么需要它</h3>
 * <p>改造前「本次扣款要不要真的发给渠道」由 <b>Payment 侧</b>决定——
 * {@code PaymentController} 自己读 {@code mockCashier.isEnabled()} 再结合
 * {@code channelGateway.isSandboxRequest()} 算出 {@code defer} 布尔，传给应用服务。
 * 那意味着「什么是 mock 环境」「沙箱要不要延迟」这些<b>渠道域的知识</b>漏在了资金动作域，
 * 而且漏在了 Controller 里（连应用服务都没进）。</p>
 *
 * <p>本接口把裁决点收回渠道网关域：Payment 侧只说「我要对这笔扣款」，
 * <b>是否真的下发、还是直接给出一个让买家去付款的凭证</b>，由网关域在本策略内回答。</p>
 *
 * <h3>与「渠道插件自己返回凭证」的区别</h3>
 * <p>也可以让 mock 渠道插件在演示模式下返回收银台凭证。但那样渠道实现<b>会被真实触达</b>，
 * 而既有语义（{@code PaymentDeferredChannelTest}）要求「延迟路径下渠道绝不能被触达」——
 * 那不是洁癖：延迟路径的存在意义就是「平台尚未向渠道发起扣款」，
 * 一旦触达，{@code channel_orders} 就该有一条真实渠道交互记录，与「未发起」自相矛盾。
 * 故裁决 MUST 发生在解析渠道实现<b>之前</b>，即本层。</p>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>返回 {@code Optional.empty()} ⇒ 正常派发（解析渠道实现并调用 {@code charge}）；</li>
 *   <li>返回非空凭证 ⇒ <b>不触达渠道实现</b>，直接以「渠道已受理、买家尚未付款」
 *       （{@code ChannelResult.accepted}）返回，payment 停 {@code PROCESSING}。</li>
 * </ul>
 */
public interface ChargeDispatchPolicy {

    /**
     * 本次扣款是否要在网关内截住并直接给出付款凭证。
     *
     * @param request 扣款请求（含 paymentNo / orderNo / 金额 / 币种 / 渠道码）
     * @return 非空即凭证（不触达渠道实现）；空即正常派发
     */
    Optional<PayCredential> deferredCredential(ChargeRequest request);
}
