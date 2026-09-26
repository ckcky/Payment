package com.payment.channelgateway.domain;

import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ChannelOrder.channelNo}（spec 037 / FR-001 / FR-002 / SC-003）。
 *
 * <p>渠道网关自有业务单号 {@code channelNo}（{@code CH} + 雪花）取代跨域契约中的数值主键
 * {@code attemptId}。本测试钉住四件事：① 必填；② 不可变；③ 与 {@code paymentNo} 同构且可通过
 * {@link BusinessNos#isValid} 校验；④ 每次尝试各自独立。</p>
 *
 * <p><b>为什么必须有单号</b>：ADR-0063 规定跨系统标识一律业务单号，禁止数值 ID。
 * {@code channel_orders.id} 是数据库自增主键，把它传给渠道即重演 C-12 / S21 事故
 * （错把平台单号当渠道单号）。</p>
 */
class ChannelOrderChannelNoTest {

    private static final String PAYMENT_NO = "PM202609250000000001";
    private static final String CHANNEL_CODE = "MOCK";

    /** FR-001：channelNo 必填（null 即拒绝）。 */
    @Test
    @DisplayName("channelNo 必填：null ⇒ 构造失败")
    void channelNoIsMandatory() {
        assertThatThrownBy(() -> new ChannelOrder(PAYMENT_NO, null, CHANNEL_CODE, 0, 100L, "CNY"))
                .as("channelNo 是渠道网关的业务单号，缺失即无法跨域定位（FR-001）")
                .isInstanceOf(NullPointerException.class);
    }

    /** FR-001 / SC-003：channelNo 为 CH + 雪花，且能通过自身类型的 isValid 校验。 */
    @Test
    @DisplayName("channelNo 前缀 CH 且通过 BusinessNos.isValid 校验（SC-003）")
    void channelNoIsAValidChannelBusinessNo() {
        ChannelOrder attempt = new ChannelOrder(PAYMENT_NO, CHANNEL_CODE, 0, 100L, "CNY");

        String channelNo = attempt.getChannelNo();
        assertThat(channelNo).startsWith("CH");
        assertThat(BusinessNos.isValid(channelNo, BusinessNoType.CHANNEL))
                .as("channelNo 必须能被自身类型校验通过，实际=" + channelNo)
                .isTrue();
        assertThat(BusinessNos.isValid(channelNo, BusinessNoType.PAYMENT))
                .as("前缀不匹配的类型不得通过")
                .isFalse();
    }

    /** FR-002：channelNo 是写入期确定、之后不可变的事实（字段 final，无 setter）。 */
    @Test
    @DisplayName("channelNo 不可变：字段 final 且无 setter")
    void channelNoIsImmutable() throws Exception {
        Field field = ChannelOrder.class.getDeclaredField("channelNo");
        assertThat(Modifier.isFinal(field.getModifiers()))
                .as("channelNo 一经铸造即不可改写")
                .isTrue();
        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();

        List<String> methods = Arrays.stream(ChannelOrder.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();
        assertThat(methods)
                .as("不得存在 setChannelNo —— 单号一旦铸造就是事实")
                .doesNotContain("setChannelNo");
    }

    /** 每次尝试各自持有独立单号（1 次渠道交互 = 1 张网关单）。 */
    @Test
    @DisplayName("两次尝试的 channelNo 互不相同")
    void eachAttemptOwnsItsOwnChannelNo() {
        ChannelOrder first = new ChannelOrder(PAYMENT_NO, CHANNEL_CODE, 0, 100L, "CNY");
        ChannelOrder second = new ChannelOrder(PAYMENT_NO, CHANNEL_CODE, 1, 100L, "CNY");

        assertThat(first.getChannelNo()).isNotEqualTo(second.getChannelNo());
    }

    /** 退款渠道尝试同样持有自己的 channelNo（复用本表，但网关单独立）。 */
    @Test
    @DisplayName("refundAttempt 也携带合法 channelNo")
    void refundAttemptCarriesChannelNo() {
        ChannelOrder attempt = ChannelOrder.refundAttempt(PAYMENT_NO, CHANNEL_CODE, 100L, "CNY");

        assertThat(attempt.getChannelNo()).startsWith("CH");
        assertThat(BusinessNos.isValid(attempt.getChannelNo(), BusinessNoType.CHANNEL)).isTrue();
        assertThat(attempt.getAttemptType()).isEqualTo(ChannelOrder.TYPE_REFUND);
    }

    /** 持久化重建：显式传入的 channelNo 被原样还原（回读不重新铸造）。 */
    @Test
    @DisplayName("rehydrate 显式还原 channelNo（回读不重新铸造）")
    void rehydrateRestoresExplicitChannelNo() {
        String persisted = BusinessNos.of(BusinessNoType.CHANNEL);

        ChannelOrder attempt = ChannelOrder.rehydrate(10L, PAYMENT_NO, persisted, CHANNEL_CODE, 0,
                null, null, "ch-ref", ChannelOrderStatus.ACCEPTED, null, null, 1,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", null);

        assertThat(attempt.getChannelNo()).isEqualTo(persisted);
    }

    /** 兼容重载（spec 037 前的 13 参形态）：channelNo 自动铸造，既有调用点零改动。 */
    @Test
    @DisplayName("rehydrate 旧签名兼容：channelNo 自动铸造且合法")
    void rehydrateLegacySignatureMintsChannelNo() {
        ChannelOrder attempt = ChannelOrder.rehydrate(10L, PAYMENT_NO, CHANNEL_CODE, 0,
                null, null, "ch-ref", ChannelOrderStatus.ACCEPTED, null, null, 1, 100L, "CNY");

        assertThat(attempt.getChannelNo()).isNotNull();
        assertThat(BusinessNos.isValid(attempt.getChannelNo(), BusinessNoType.CHANNEL))
                .as("兼容路径也必须产出合法单号（channel_no 列 NOT NULL）")
                .isTrue();
    }
}
