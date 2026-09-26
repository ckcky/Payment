package com.payment.channelgateway.application;

import com.payment.common.dto.channel.Goods;
import com.payment.common.dto.channel.Payer;
import com.payment.common.dto.channel.PaymentScene;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道扣款请求（平台 → 渠道）。渠道实现只读取必要字段，不访问支付聚合内部状态。
 *
 * <p>跨系统标识一律业务单号（ADR-0063）：{@code paymentNo}（PM+雪花），禁止数值 paymentId。</p>
 *
 * <h3>spec 030 契约扩展（FR-105 / FR-108）</h3>
 * 由 4 字段扩为 11 字段，以容纳真实渠道必填项（支付宝 {@code subject}、微信
 * {@code description} + {@code payer.openid}、Stripe {@code payment_method_types}）。
 * <b>保留 4 参兼容构造器</b>——既有构造点零改动（SC-A-02）。
 *
 * <p>{@code channelExtra} 是渠道原生参数的扩展袋（{@code Map<String,String>}，<b>可空</b>）：
 * 键名用渠道原生参数名，便于排障时与渠道文档 / 报文逐项比对。</p>
 *
 * <h3>spec 037 / T3 重构：移除 {@code attemptId}</h3>
 * 原第 2 个分量 {@code Long attemptId} 是 {@code channel_orders.id}（数据库自增主键），
 * 它出现在跨域契约里<b>本身违反 ADR-0063</b>（「跨系统标识一律业务单号，禁止数值 ID」），
 * 且历史已踩 C-12 / S21（把平台侧单号当渠道侧单号传）。
 * 渠道网关自己的业务身份是 {@code channelNo}（CH+雪花，spec 037 / FR-001），
 * 它由渠道单承载，<b>不再经本契约传递数值主键</b>。
 *
 * <h3>spec 041：追加 {@code orderNo}，移除 {@code callbackUrls}</h3>
 * <ul>
 *   <li><b>追加 {@code orderNo}</b>：真实渠道下单本就需要「商户订单号」，此前本契约缺失该字段，
 *       导致渠道侧无法拼装任何与订单相关的展示信息。</li>
 *   <li><b>移除 {@code callbackUrls}</b>：回调地址是<b>渠道协议自己的事</b>——通知地址怎么写、
 *       要不要拼单号、跳回地址是否必需，每家渠道都不同（支付宝要 notify+return、
 *       Stripe 只认 return、微信只要 notify）。此前由 payment 层读全局配置拼好再下发，
 *       等于让资金动作域替渠道域决定协议细节。<b>spec 041 起由各渠道 plugin 读自己的配置自行拼装</b>。</li>
 * </ul>
 */
public record ChargeRequest(String paymentNo, long amountMinor,
                            String currencyCode, String channelCode,
                            PaymentScene scene, Goods goods,
                            Instant expireAt, Payer payer, String attach,
                            Map<String, String> channelExtra,
                            /** spec 041：平台侧订单号（可空，兼容构造下为 null）。 */
                            String orderNo) {

    /**
     * 兼容构造器（spec 030 前的既有形态）：仅必要字段，扩展字段一律 {@code null}。
     *
     * <p>既有调用点（含测试桩）零改动即可编译；渠道实现 MUST 对 {@code null} 的
     * 扩展字段做容错（不校验场景、不拼商品信息）。</p>
     */
    public ChargeRequest(String paymentNo, long amountMinor,
                         String currencyCode, String channelCode) {
        this(paymentNo, amountMinor, currencyCode, channelCode,
                null, null, null, null, null, null, null);
    }

    /**
     * 兼容构造器（spec 030 / FR-105 的 10 参形态）：无 {@code orderNo}、无 {@code callbackUrls}。
     *
     * <p>spec 041 移除 {@code callbackUrls} 前，既有 11 参构造点形如
     * {@code (…, scene, goods, callbackUrls, expireAt, payer, attach, extra)}。
     * 该形态<b>已随 spec 041 一并删除</b>——回调地址不再是平台侧下发的入参。</p>
     */
    public ChargeRequest(String paymentNo, long amountMinor,
                         String currencyCode, String channelCode,
                         PaymentScene scene, Goods goods,
                         Instant expireAt, Payer payer, String attach,
                         Map<String, String> channelExtra) {
        this(paymentNo, amountMinor, currencyCode, channelCode, scene, goods,
                expireAt, payer, attach, channelExtra, null);
    }
}
