package com.payment.common.core.dye;

/**
 * 链路染色模态（spec 030 / ADR-0076，FR-160）。
 *
 * <p>同一 {@code channelCode} 下可以有<b>两种协议实现</b>：本地 mock 与真实渠道。
 * 染色决定单次请求走哪一种。</p>
 *
 * <h3>缺省语义（安全默认，硬约束）</h3>
 * <b>不染色 = {@link #MOCK}</b>——绝不误连真实渠道。解析 {@code null} 或空白返回 {@link #MOCK}，
 * 但<b>非法值必须抛异常</b>（ADR-0049 第 2 条：配错不许静默走默认）。
 */
public enum DyeMode {

    /** 本地模拟：走 {@code AbstractMockChannelAdapter} 的模拟语义（缺省，安全默认）。 */
    MOCK,
    /** 真实渠道沙箱：走真实渠道协议（当前仅支付宝）。 */
    SANDBOX;

    /**
     * 大小写不敏感严格解析。
     *
     * @param value 请求头取值（{@code null} 或空白 ⇒ {@link #MOCK}）
     * @return 解析后的模态
     * @throws IllegalArgumentException 非 {@code null}/空白但不在取值集合内
     */
    public static DyeMode parse(String value) {
        if (value == null || value.isBlank()) {
            return MOCK; // 缺省 = MOCK：绝不误连真实渠道
        }
        String normalized = value.trim();
        for (DyeMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "invalid X-Dye-Tag: '" + value + "'; expected one of MOCK, SANDBOX");
    }
}
