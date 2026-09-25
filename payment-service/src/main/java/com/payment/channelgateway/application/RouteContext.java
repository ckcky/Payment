package com.payment.channelgateway.application;

/**
 * 一次选路的输入上下文（Feature 028 / FR-017，ADR-0073）。
 *
 * <p><b>一期只有 {@code requestedChannelCode} 参与决策。</b></p>
 *
 * @param amountMinor          本次支付金额（最小货币单位）—— <b>当前未使用</b>，
 *                             为将来加「金额条件规则」（如大额走某渠道）预留签名。
 *                             误认为已生效是常见误读，故在此显式标注。
 * @param currencyCode         币种 —— <b>当前未使用</b>，同为条件规则预留。
 *                             误认为已生效是常见误读，故在此显式标注。
 * @param requestedChannelCode 调用方显式指定的渠道码；{@code null} / 空 = 只表达支付意图，由平台选路。
 *                             <b>非空即优先返回</b>（US3：Router 对显式意图零干预）。
 */
public record RouteContext(long amountMinor, String currencyCode, String requestedChannelCode) {

    /** 便捷构造：不指定渠道（US2 主路径）。 */
    public static RouteContext auto(long amountMinor, String currencyCode) {
        return new RouteContext(amountMinor, currencyCode, null);
    }

    /** 是否为显式指定渠道（US3）。 */
    public boolean hasRequestedCode() {
        return requestedChannelCode != null && !requestedChannelCode.isBlank();
    }
}
