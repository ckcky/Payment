package com.payment.reconciliation.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.List;

/**
 * 渠道资金事实值对象（spec 032 §6.2，G3 载荷来源）：账单侧聚合出的
 * 「渠道实收净额 + 渠道手续费」，是 {@code CHANNEL_SETTLEMENT} / {@code CHANNEL_FEE}
 * 两类事件的发生依据（031 §16 挂起项的收口）。
 *
 * <p>聚合口径（plan §2.6 双计防线）：</p>
 * <ul>
 *   <li>{@code netReceivedMinor} = Σ PAYMENT − Σ REFUND + Σ SETTLEMENT（带符号，渠道实付净额）；</li>
 *   <li>{@code channelFeeMinor} = Σ FEE 行（费用本体 = feeMinor，缺省取 amountMinor）；</li>
 *   <li>PAYMENT/REFUND 行的 {@code feeMinor} 只参与 FEE_MISMATCH 判定，<b>绝不</b>进 CHANNEL_FEE
 *       聚合（渠道费唯一合法来源 = FEE 类型账单行）；</li>
 *   <li>仅 {@code SUCCEEDED} 行参与聚合（非成功行无资金移动，由匹配层/核对层出差异）；</li>
 *   <li>金额一律 long minor 单位；混币种 ⇒ {@code INVALID_ARGUMENT}（MVP 单币种）。</li>
 * </ul>
 */
public record ChannelFundFact(String channelCode, String period,
                              long netReceivedMinor, long channelFeeMinor, String currency) {

    public static ChannelFundFact aggregate(String channelCode, String period,
                                            List<ReconciliationMatching.StatementLineView> lines) {
        long net = 0L;
        long fee = 0L;
        String currency = null;
        for (ReconciliationMatching.StatementLineView line : lines == null ? List.<ReconciliationMatching.StatementLineView>of() : lines) {
            if (!"SUCCEEDED".equals(line.status())) {
                continue; // 非成功行无资金移动（差异由匹配/核对层承载）
            }
            if (currency == null) {
                currency = line.currency();
            } else if (!currency.equals(line.currency())) {
                throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                        "mixed-currency statement lines: " + currency + " vs " + line.currency());
            }
            switch (line.referenceType() == null ? "" : line.referenceType()) {
                case "PAYMENT" -> net += line.amountMinor();
                case "REFUND" -> net -= line.amountMinor();
                case "SETTLEMENT" -> net += line.amountMinor();
                case "FEE" -> fee += line.feeMinor() != 0 ? line.feeMinor() : line.amountMinor();
                default -> {
                    // UNKNOWN / 无法归一行不参与资金聚合（UNKNOWN_MAPPING 差异已承接）
                }
            }
        }
        return new ChannelFundFact(channelCode, period, net, fee, currency == null ? "CNY" : currency);
    }
}
