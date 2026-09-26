package com.payment.channel.application.spi;

import com.payment.channel.application.ChannelResult;

/**
 * 渠道回调的<b>解析产物</b>（渠道插件化内核 / SPI-03；spec 041 / T12 密封化）。
 *
 * <p>插件把渠道私有报文翻译成这个平台自有结构，内核从此只知道三件事：
 * 「哪一笔」「什么结论」「渠道说收了多少钱」。渠道字段名（{@code out_trade_no} /
 * {@code data.object.id} / {@code trade_status} / {@code type}）<b>一律不出现在内核</b>——
 * 这是「内核不认识渠道」这条要求的落点。</p>
 *
 * <h3>为什么是密封的两分支（spec 041 / FR-006）</h3>
 * <p>FR-006 要求渠道经 {@code PaymentResultPort} 交付「标准<b>支付/退款</b>结果」，
 * 而 {@code PaymentResultPort} 恰有两条入向操作（{@code onChannelPayResult} /
 * {@code onChannelRefundResult}）。此前解析产物是单一 record（只有 {@code paymentNo}），
 * 退款回调因此无法经统一入口表达，只能另立端点——那是「双路径」的根源。
 * 密封成两分支后：<b>类型即判别式</b>，内核用 {@code switch} 穷举分发，
 * 新增第三种回调（若有）会在编译期强制所有分发点处理，不会漏。</p>
 *
 * <h3>为什么不用「加一个可空字段」</h3>
 * <p>单 record 加 {@code refundNo} 会让字段可空性组合爆炸（支付却带 refundNo、
 * 退款却带 notifiedAmount……），每一种非法组合都要运行时防御；密封分支把非法态
 * 变成<b>不可表达</b>。</p>
 *
 * <h3>为什么要带 {@code notifiedAmount}</h3>
 * 金额/币种校验是<b>全渠道通用</b>的资金防线（ADR-0025 / FR-210 / FR-211），
 * 不是某个渠道的私有逻辑。若把它塞进插件，每接一家渠道都要重新实现一遍
 * 「渠道说的金额 ≠ 平台记的金额就拒绝推进」——而漏一次就是真实的资金差异。
 * 故由插件把「渠道声称的金额」<b>翻译</b>出来，由内核统一执行校验：插件只负责「读出来」，
 * 内核负责「判定并拒绝」。读不出来就给 {@link NotifiedAmount#UNKNOWN}，
 * 内核据此<b>跳过</b>金额校验而不是放行一个 {@code 0}——把「没读到」当成「0 元」
 * 会制造出一批假差异。
 *
 * <p>退款分支不带金额：退款金额的权威值是平台侧退款单上的金额，
 * 渠道通知不携带它（见 {@code ChannelRefundNotified} 的注释）。</p>
 *
 * @see ParsedPayCallback 支付回调解析产物
 * @see ParsedRefundCallback 退款回调解析产物
 */
public sealed interface ParsedCallback
        permits ParsedCallback.ParsedPayCallback, ParsedCallback.ParsedRefundCallback {

    /** 归一化后的渠道结论（两分支共有）。 */
    ChannelResult result();

    /**
     * 支付回调解析产物。
     *
     * @param paymentNo      平台支付单号（PM+雪花；ADR-0063 跨系统标识口径）
     * @param result         归一化后的渠道结果
     * @param notifiedAmount 渠道在报文里声称的金额/币种（读不出时为 {@link NotifiedAmount#UNKNOWN}）
     */
    record ParsedPayCallback(String paymentNo, ChannelResult result, NotifiedAmount notifiedAmount)
            implements ParsedCallback {

        public ParsedPayCallback {
            if (paymentNo == null || paymentNo.isBlank()) {
                throw new IllegalArgumentException("parsed pay callback must carry a paymentNo");
            }
            if (result == null) {
                throw new IllegalArgumentException("parsed pay callback must carry a ChannelResult");
            }
            notifiedAmount = notifiedAmount == null ? NotifiedAmount.UNKNOWN : notifiedAmount;
        }
    }

    /**
     * 退款回调解析产物（spec 041 / T12；FR-006 的退款半边）。
     *
     * @param refundNo 退款单号（{@code PMRF}/{@code TXRF}+雪花），退款收敛的寻址键
     * @param result   归一化后的渠道结论
     */
    record ParsedRefundCallback(String refundNo, ChannelResult result) implements ParsedCallback {

        public ParsedRefundCallback {
            if (refundNo == null || refundNo.isBlank()) {
                throw new IllegalArgumentException("parsed refund callback must carry a refundNo");
            }
            if (result == null) {
                throw new IllegalArgumentException("parsed refund callback must carry a ChannelResult");
            }
        }
    }

    /** 支付回调（无金额信息）：内核据此跳过金额校验。 */
    static ParsedPayCallback pay(String paymentNo, ChannelResult result) {
        return new ParsedPayCallback(paymentNo, result, NotifiedAmount.UNKNOWN);
    }

    /** 支付回调（带渠道声称金额）。 */
    static ParsedPayCallback pay(String paymentNo, ChannelResult result, NotifiedAmount notifiedAmount) {
        return new ParsedPayCallback(paymentNo, result, notifiedAmount);
    }

    /** 退款回调。 */
    static ParsedRefundCallback refund(String refundNo, ChannelResult result) {
        return new ParsedRefundCallback(refundNo, result);
    }

    /**
     * 支付回调的兼容工厂（spec 037 形态，等价于 {@link #pay(String, ChannelResult)}）。
     *
     * @deprecated 用 {@link #pay(String, ChannelResult)} —— 语义更明确；
     *             本工厂仅为既有调用点零改动保留。
     */
    @Deprecated
    static ParsedPayCallback of(String paymentNo, ChannelResult result) {
        return pay(paymentNo, result);
    }

    /** 渠道声称的金额（最小货币单位）与币种。 */
    record NotifiedAmount(Long amountMinor, String currencyCode) {

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
