package com.payment.payment.application;

import com.payment.common.dto.channel.ChannelPayNotified;
import com.payment.common.dto.channel.ChannelRefundNotified;

/**
 * 渠道 → Payment 的<b>入向端口</b>（spec 037 / FR-010 / FR-012 / INV-2）。
 *
 * <h3>它修正了什么：接口定义权的方向</h3>
 * <p>改造前是 {@code RefundResultListener}：接口定义在<b>渠道包</b>、实现却在<b>退款应用服务</b>，
 * 于是「渠道」在编译期反向驱动「退款」——谁定义接口，谁就掌握演进的主动权。本接口把定义权
 * 交回 Payment：<b>Payment 定义并实现</b>，渠道网关域只依赖接口（FR-012 / INV-2）。</p>
 *
 * <h3>契约纯度</h3>
 * <p>两个入向事件的类型全部来自 {@code common-dto}（{@link ChannelPayNotified} /
 * {@link ChannelRefundNotified}）——渠道私有类型（渠道报文结构、渠道状态码、SDK 异常）
 * <b>一律不出现</b>（SC-004）。网关侧负责把渠道报文翻译成这两个结构，Payment 侧只认平台语义。</p>
 *
 * <h3>为什么把业务校验放在这里（FR-011）</h3>
 * <p>原先 {@code ChannelPluginCallbackController.validate()} 里直接查 payment、验渠道引用归属、
 * 验金额币种——那是 <b>Payment 的业务规则</b>写在<b>渠道域的回调入口</b>里。规则跟着数据走：
 * 钱的事实属于 Payment，校验就该属于 Payment。迁到本端口的实现后，回调入口只剩
 * 「收报文、交网关」。</p>
 *
 * @see PayNotifyOutcome 支付回调的处理结论
 */
public interface PaymentNotifyPort {

    /**
     * 渠道支付结果通知。
     *
     * <p>实现 MUST 依次完成：业务校验（单据存在 / 渠道引用归属 / 金额币种）→ 终态收敛
     * （复用 {@code PaymentCallbackService}，终态吸收与幂等由它保证）→ 返回结论。</p>
     *
     * <p>校验失败 MUST <b>不推进任何状态</b>，并留下「三件套」痕迹（计指标 + 写审计 + 记日志）；
     * 实现 MUST NOT 抛出异常给渠道网关——把异常转成 {@link PayNotifyOutcome#error(String)}，
     * 由网关统一转成非成功应答（渠道重推是恢复手段，不是错误处理）。</p>
     *
     * @param notified 渠道网关翻译后的支付结论（含 {@code channelCode}，FR-006）
     * @return 处理结论，供网关构造对渠道的应答体
     */
    PayNotifyOutcome onChannelPayResult(ChannelPayNotified notified);

    /**
     * 渠道退款结果通知（取代 {@code RefundResultListener#onChannelRefundResult}，FR-012）。
     *
     * <p>进程内 Mock 渠道的「受理 + 异步推送」与真实渠道的 HTTP 回调走<b>同一条</b>收敛路径
     * （{@code RefundResultProcessor}），语义等价，不留双路径。</p>
     *
     * <p>返回 {@code void}：退款回调不参与「渠道应答体」的构造——Mock 推送没有应答体，
     * 真实渠道的应答由回调端点按协议自行返回。</p>
     *
     * @param notified 渠道网关翻译后的退款结论（含 {@code channelCode}，FR-006）
     */
    void onChannelRefundResult(ChannelRefundNotified notified);
}
