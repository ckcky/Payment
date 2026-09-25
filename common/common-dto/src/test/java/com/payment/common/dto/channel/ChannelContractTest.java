package com.payment.common.dto.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道网关跨域契约元数据（spec 037 / FR-003~FR-006）。
 *
 * <p>钉住四件事：① 契约位置 = common-dto（FR-003）；② 查询以 {@code channelNo} 为主键（FR-005）；
 * ③ 回调通知携带 {@code channelCode}（FR-006）；④ 跨域标识不含数值主键（INV-4 / ADR-0063）。</p>
 *
 * <p>与 {@link com.payment.common.dto.rpc.RpcContractTest} 同体例：只断言契约元数据，
 * 不复制业务逻辑。</p>
 */
class ChannelContractTest {

    /** FR-004 列举的 8 个跨域契约（出向 3 + 回执 3 + 回调通知 2）。 */
    private static final List<Class<?>> CONTRACTS = List.of(
            ChannelPayCommand.class,
            ChannelRefundCommand.class,
            ChannelQueryCommand.class,
            ChannelPayReceipt.class,
            ChannelRefundReceipt.class,
            ChannelQuerySnapshot.class,
            ChannelPayNotified.class,
            ChannelRefundNotified.class);

    /** 出向契约（Payment → 渠道网关）。 */
    private static final List<Class<?>> OUTBOUND = List.of(
            ChannelPayCommand.class, ChannelRefundCommand.class, ChannelQueryCommand.class);

    /** 入向契约（渠道网关 → Payment）：回执 + 回调通知。 */
    private static final List<Class<?>> INBOUND = List.of(
            ChannelPayReceipt.class, ChannelRefundReceipt.class, ChannelQuerySnapshot.class,
            ChannelPayNotified.class, ChannelRefundNotified.class);

    /**
     * 与契约同包的共享值类型（spec 030 既有，T2 一并下沉 {@code com.payment.common.dto.channel}）。
     *
     * <p>它们此前物理位于 {@code common-dto} 却声明旧包名 {@code com.payment.payment.application.channel}
     * ——跨域契约引用 {@code com.payment.payment.*} 是边界倒置，故 T2 的「重构」步把它们归位。</p>
     */
    private static final List<Class<?>> SHARED_VALUE_TYPES = List.of(
            Goods.class, Payer.class, CallbackUrls.class, PaymentScene.class, PayCredential.class);

    /** FR-003：跨域契约 MUST 位于 common-dto 的 {@code dto/channel} 包。 */
    @Test
    void allContractsResideInCommonDtoChannelPackage() {
        for (Class<?> contract : CONTRACTS) {
            assertThat(contract.getPackageName())
                    .as("%s 必须位于 common-dto 跨域契约包（FR-003）", contract.getSimpleName())
                    .isEqualTo("com.payment.common.dto.channel");
        }
    }

    /** FR-004：8 个契约 MUST 都是不可变 record（无 setter、构造期校验）。 */
    @Test
    void allContractsAreRecords() {
        for (Class<?> contract : CONTRACTS) {
            assertThat(contract.isRecord())
                    .as("%s 必须是 record（FR-004）", contract.getSimpleName())
                    .isTrue();
        }
    }

    /** FR-005：查询类契约 MUST 以 {@code channelNo} 为主键。 */
    @Test
    void queryCommandIsKeyedByChannelNo() {
        RecordComponent[] components = ChannelQueryCommand.class.getRecordComponents();
        assertThat(components).as("ChannelQueryCommand 必须是 record").isNotEmpty();
        assertThat(components[0].getName())
                .as("FR-005：查询类契约 MUST 以 channelNo 为主键（且为首个分量）")
                .isEqualTo("channelNo");
    }

    /** FR-005：查询快照 MUST 回指 {@code channelNo}（而非 {@code outTradeNo} 等渠道私有口径）。 */
    @Test
    void querySnapshotCarriesChannelNo() {
        assertThat(componentNames(ChannelQuerySnapshot.class))
                .as("FR-005：查询快照 MUST 以 channelNo 回指被查询的网关单")
                .contains("channelNo");
    }

    /** FR-006：支付回调通知 MUST 携带 {@code channelCode}。 */
    @Test
    void payNotifiedCarriesChannelCode() {
        assertThat(componentNames(ChannelPayNotified.class))
                .as("FR-006：支付回调通知 MUST 携带 channelCode")
                .contains("channelCode");
    }

    /** FR-006：退款回调通知 MUST 携带 {@code channelCode}。 */
    @Test
    void refundNotifiedCarriesChannelCode() {
        assertThat(componentNames(ChannelRefundNotified.class))
                .as("FR-006：退款回调通知 MUST 携带 channelCode")
                .contains("channelCode");
    }

    /** INV-4 / ADR-0063：出向契约不得出现任何 {@code Long} 分量（数值主键的载体）。 */
    @Test
    void outboundContractsCarryNoNumericPrimaryKey() {
        for (Class<?> contract : OUTBOUND) {
            assertThat(Arrays.stream(contract.getRecordComponents())
                    .map(component -> component.getType().getSimpleName()))
                    .as("%s 不得含 Long 数值主键（INV-4 / ADR-0063）", contract.getSimpleName())
                    .doesNotContain("Long");
        }
    }

    /**
     * INV-4 / ADR-0063：任何契约的 {@code *Id} / {@code id} 分量都 MUST 是业务单号（String），
     * <b>不得</b>是数值类型。
     *
     * <p>覆盖出向 + 入向全部 8 个契约：{@code attemptId} 这类自增主键若混入，
     * 本断言即报红。</p>
     */
    @Test
    void noContractCarriesNumericIdentifierComponent() {
        for (Class<?> contract : CONTRACTS) {
            Arrays.stream(contract.getRecordComponents())
                    .filter(component -> component.getName().toLowerCase().endsWith("id"))
                    .forEach(component -> assertThat(component.getType().getSimpleName())
                            .as("%s.%s 是标识分量，必须是业务单号 String（INV-4 / ADR-0063）",
                                    contract.getSimpleName(), component.getName())
                            .isEqualTo("String"));
        }
    }

    /** T2 重构：共享值类型 MUST 与契约同包（不得再挂在 {@code com.payment.payment.*} 下）。 */
    @Test
    void sharedValueTypesResideInCommonDtoChannelPackage() {
        for (Class<?> valueType : SHARED_VALUE_TYPES) {
            assertThat(valueType.getPackageName())
                    .as("%s 必须与跨域契约同包（INV-3：跨域类型一律来自 common-dto）",
                            valueType.getSimpleName())
                    .isEqualTo("com.payment.common.dto.channel");
        }
    }

    private static Set<String> componentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
