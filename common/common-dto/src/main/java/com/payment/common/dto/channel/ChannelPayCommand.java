package com.payment.common.dto.channel;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道扣款指令（Payment → 渠道网关，spec 037 / FR-004）。
 *
 * <p>本契约是跨域（cross-domain）契约，位于 {@code common-dto}（FR-003 / INV-3）：
 * 将来渠道网关真拆成独立服务时，两域依赖同一个 jar 即可，契约零改造。</p>
 *
 * <h3>为什么第一个分量是 {@code channelNo} 而不是 {@code attemptId}</h3>
 * <p>旧形态 {@code ChargeRequest(paymentNo, attemptId, ...)} 里的 {@code attemptId} 是
 * {@code payment_attempts.id}——数据库自增<b>数值主键</b>，违反 ADR-0063「跨系统标识一律业务单号，
 * 禁止数值 ID」，且历史已踩过 C-12 / S21 事故（把平台单号当渠道单号传）。
 * 本契约以网关自有业务单号 {@code channelNo}（{@code CH} + 雪花，FR-001）取代之。</p>
 *
 * <p><b>本 record 内不得出现任何数值主键</b>（{@code Long} 分量）——由
 * {@code ChannelContractTest#outboundContractsCarryNoNumericPrimaryKey} 钉死。</p>
 *
 * @param channelNo    渠道网关业务单号（{@code CH} + 雪花，FR-001），<b>必填</b>
 * @param paymentNo    平台支付单号（{@code PM} + 雪花，ADR-0063）
 * @param amountMinor  金额（最小货币单位，<b>整型</b>，禁止 float/double）
 * @param currencyCode 币种（ISO-4217 三字母）
 * @param channelCode  渠道编号（路由键，ADR-0072）
 * @param scene        支付场景（{@code null} = 不指定，渠道按其默认处理）
 * @param goods        商品信息（支付宝 {@code subject} / 微信 {@code description} 必填）
 * @param callbackUrls 回调地址对（{@code notifyUrl} 是<b>唯一</b>资金事实来源）
 * @param expireAt     订单过期时刻（{@code null} = 渠道默认）
 * @param payer        付款人信息（JSAPI / MINI_PROGRAM 场景通常必填）
 * @param attach       商户附加数据（渠道原样回传）
 * @param channelExtra 渠道原生参数扩展袋（键名用渠道原生参数名，便于与渠道报文逐项比对）
 */
public record ChannelPayCommand(String channelNo, String paymentNo, long amountMinor,
                                String currencyCode, String channelCode,
                                PaymentScene scene, Goods goods, CallbackUrls callbackUrls,
                                Instant expireAt, Payer payer, String attach,
                                Map<String, String> channelExtra) {

    public ChannelPayCommand {
        requireText(channelNo, "channelNo");
        requireText(paymentNo, "paymentNo");
        requireText(channelCode, "channelCode");
        channelExtra = channelExtra == null ? Map.of() : Map.copyOf(channelExtra);
    }

    /** 极简形态（既有 mock / 沙箱场景够用）：只给路由与金额，扩展字段一律 {@code null}。 */
    public static ChannelPayCommand of(String channelNo, String paymentNo, long amountMinor,
                                       String currencyCode, String channelCode) {
        return new ChannelPayCommand(channelNo, paymentNo, amountMinor, currencyCode, channelCode,
                null, null, null, null, null, null, null);
    }

    /** 跨域标识 MUST 为业务单号且非空（INV-4 / ADR-0063）。 */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("channel contract field must be non-blank: " + field);
        }
    }
}
