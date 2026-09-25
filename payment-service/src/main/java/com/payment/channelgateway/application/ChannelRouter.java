package com.payment.channelgateway.application;

/**
 * 支付渠道路由器（Feature 028 / FR-016，ADR-0073）：给定上下文，选出一个渠道码。
 *
 * <p><b>只产出 {@code channelCode} 字符串，不接触渠道协议</b>（INV-4）——渠道协议实现留在
 * {@code infra/channel/**}，本端口所在包 MUST NOT 依赖 {@code infra/channel/**}。</p>
 *
 * <p><b>三条硬约束</b>：
 * <ul>
 *   <li><b>确定性（INV-3 / FR-021）</b>：相同 {@link RouteContext} MUST 返回相同结果。
 *       实现中禁止随机数、时间、进程级计数器参与决策。</li>
 *   <li><b>不改选（FR-022）</b>：渠道调用失败后 MUST NOT 改选其他渠道——与 INV-2 互斥，
 *       自动降级属人类决策边界。</li>
 *   <li><b>不反向使用（INV-6）</b>：退款 / 重试 / 主动查询 MUST 按 attempt 记录解析渠道，
 *       <b>禁止调用本端口</b>。</li>
 * </ul>
 */
public interface ChannelRouter {

    /**
     * 选出一个渠道码（保证<b>已注册</b>）。
     *
     * @return 大写渠道码
     * @throws com.payment.common.core.error.BizException
     *         {@code NO_AVAILABLE_CHANNEL}（HTTP 409）—— 候选集为空；
     *         {@code CHANNEL_UNAVAILABLE}（HTTP 409）—— 显式指定的渠道当前 DOWN；
     *         {@code INVALID_ARGUMENT}（HTTP 400）—— 显式指定的渠道未注册，
     *         或 {@code routing.enabled=false} 且未指定渠道。
     */
    String route(RouteContext context);
}
