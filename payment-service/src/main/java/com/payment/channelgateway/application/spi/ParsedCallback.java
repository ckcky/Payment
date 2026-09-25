package com.payment.channelgateway.application.spi;

import com.payment.channelgateway.application.ChannelResult;

/**
 * 渠道回调的<b>解析产物</b>（渠道插件化内核 / SPI-03）。
 *
 * <p>插件把渠道私有报文翻译成这个平台自有结构，内核从此只知道三件事：
 * 「哪一笔」「什么结论」「渠道说收了多少钱」。渠道字段名（{@code out_trade_no} /
 * {@code data.object.id} / {@code trade_status} / {@code type}）<b>一律不出现在内核</b>——
 * 这是「内核不认识渠道」这条要求的落点。</p>
 *
 * <h3>为什么要带 {@code notifiedAmount}</h3>
 * 金额/币种校验是<b>全渠道通用</b>的资金防线（ADR-0025 / FR-210 / FR-211），
 * 不是某个渠道的私有逻辑。若把它塞进插件，每接一家渠道都要重新实现一遍
 * 「渠道说的金额 ≠ 平台记的金额就拒绝推进」——而漏一次就是真实的资金差异。
 * 故由插件把「渠道声称的金额」<b>翻译</b>出来，由内核统一执行校验：</p>
 * <ul>
 *   <li>插件只负责「读出来」；</li>
 *   <li>内核负责「判定并拒绝」。</li>
 * </ul>
 * 读不出来就给 {@link NotifiedAmount#UNKNOWN}（例如某些渠道的状态通知不带金额），
 * 内核据此<b>跳过</b>金额校验而不是放行一个 {@code 0}——把「没读到」当成「0 元」
 * 会制造出一批假差异。
 *
 * @param paymentNo 平台支付单号（PM+雪花；ADR-0063 跨系统标识口径）
 * @param result    归一化后的渠道结果
 * @param notifiedAmount 渠道在报文里声称的金额/币种（读不出时为 {@link NotifiedAmount#UNKNOWN}）
 */
public record ParsedCallback(String paymentNo, ChannelResult result, NotifiedAmount notifiedAmount) {

    public ParsedCallback {
        if (paymentNo == null || paymentNo.isBlank()) {
            throw new IllegalArgumentException("parsed callback must carry a paymentNo");
        }
        if (result == null) {
            throw new IllegalArgumentException("parsed callback must carry a ChannelResult");
        }
        notifiedAmount = notifiedAmount == null ? NotifiedAmount.UNKNOWN : notifiedAmount;
    }

    /** 无金额信息（渠道通知未携带金额）时的工厂：内核据此跳过金额校验。 */
    public static ParsedCallback of(String paymentNo, ChannelResult result) {
        return new ParsedCallback(paymentNo, result, NotifiedAmount.UNKNOWN);
    }

    /** 渠道声称的金额（最小货币单位）与币种。 */
    public record NotifiedAmount(Long amountMinor, String currencyCode) {

        /** 「报文里没有金额信息」——<b>不是</b>「金额为 0」，两者语义完全不同。 */
        public static final NotifiedAmount UNKNOWN = new NotifiedAmount(null, null);

        public static NotifiedAmount of(long amountMinor, String currencyCode) {
            return new NotifiedAmount(amountMinor, currencyCode);
        }

        /** 是否可用于金额校验（两者都读到才算数）。 */
        public boolean isKnown() {
            return amountMinor != null && currencyCode != null && !currencyCode.isBlank();
        }
    }
}
