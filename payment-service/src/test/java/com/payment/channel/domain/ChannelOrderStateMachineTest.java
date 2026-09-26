package com.payment.channel.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ChannelOrder} 状态机（spec 041 / FR-004 / INV-002 / INV-004 / INV-005 / §8）。
 *
 * <p>spec §8 硬约束：本 Feature <b>不得更改</b>状态枚举、迁移条件、终态定义或资金语义。
 * 本测试把现状合法迁移逐条锁死，作为重构期回归网：
 * 任何对状态机的无意改动都会在这里变红，而不是溜进资金路径。</p>
 *
 * <p>锁定的迁移图（终态 SUCCEEDED / FAILED 吸收一切迟到/重复/冲突结果，返回 {@code false}）：</p>
 * <pre>
 *   PENDING ──accept──▶ ACCEPTED ──succeed──▶ SUCCEEDED
 *      │                    ├──fail──────▶ FAILED
 *      │                    └──markUnknown▶ UNKNOWN
 *      ├──succeed（收银台路径允许 PENDING 直落终态）──▶ SUCCEEDED
 *      ├──fail────────────────────────────────────▶ FAILED
 *      └──markUnknown─────────────────────────────▶ UNKNOWN
 *   UNKNOWN ──succeed──▶ SUCCEEDED（查询/回调收敛）
 *        └───fail──────▶ FAILED
 * </pre>
 *
 * <p>幂等语义（INV-004）：同一权威结果重复施加，第二次起一律吸收（返回 {@code false}），
 * 状态、引用与失败原因均不被覆盖。</p>
 */
class ChannelOrderStateMachineTest {

    private static final String PAYMENT_NO = "PM202609260000000001";

    private static ChannelOrder fresh() {
        // 显式构造（生产写入口形态）：channelNo 由调用方铸造
        return new ChannelOrder(PAYMENT_NO, "CH202609260000000001", "MOCK", 0, 100L, "CNY");
    }

    // ===================== PENDING → ACCEPTED =====================

    @Test
    @DisplayName("PENDING → accept：记录渠道引用与响应时间（FR-004 收敛步骤）")
    void pendingAcceptsIntoAccepted() {
        ChannelOrder order = fresh();

        boolean changed = order.accept("ch-ref-1");

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.ACCEPTED);
        assertThat(order.getChannelReference()).isEqualTo("ch-ref-1");
        assertThat(order.getRespondedAt()).isNotNull();
    }

    @Test
    @DisplayName("幂等：重复 accept 被吸收（第二次返回 false，引用不被覆盖）")
    void duplicateAcceptIsAbsorbed() {
        ChannelOrder order = fresh();
        order.accept("ch-ref-1");

        boolean changed = order.accept("ch-ref-2");

        assertThat(changed).isFalse();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.ACCEPTED);
        assertThat(order.getChannelReference()).isEqualTo("ch-ref-1");
    }

    // ===================== 收敛到 SUCCEEDED =====================

    @Test
    @DisplayName("ACCEPTED → succeed：落终态并清空受理期占位失败文案")
    void acceptedSucceeds() {
        ChannelOrder order = fresh();
        order.accept("ch-ref-1");

        boolean changed = order.succeed();

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
        assertThat(order.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("收银台路径：PENDING 可直接 succeed（取得引用前收到权威结果）")
    void pendingCanSucceedDirectly() {
        ChannelOrder order = fresh();

        boolean changed = order.succeed();

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("UNKNOWN → succeed：查询/回调收敛回成功")
    void unknownConvergesToSucceeded() {
        ChannelOrder order = fresh();
        order.markUnknown("timeout");

        boolean changed = order.succeed();

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
        assertThat(order.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("终态吸收：SUCCEEDED 后再 succeed 返回 false（INV-002 / INV-004）")
    void terminalSucceededAbsorbsRepeatSuccess() {
        ChannelOrder order = fresh();
        order.succeed();

        assertThat(order.succeed()).isFalse();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("终态冲突吸收：SUCCEEDED 后迟到 fail 不得覆盖（绝不由失败顶替成功）")
    void terminalSucceededAbsorbsConflictingFailure() {
        ChannelOrder order = fresh();
        order.succeed();

        boolean changed = order.fail("late failure");

        assertThat(changed).isFalse();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
        assertThat(order.getFailureReason()).isNull();
    }

    // ===================== 收敛到 FAILED =====================

    @Test
    @DisplayName("ACCEPTED → fail：落终态并记录权威失败原因")
    void acceptedFailsWithReason() {
        ChannelOrder order = fresh();
        order.accept("ch-ref-1");

        boolean changed = order.fail("insufficient balance");

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.FAILED);
        assertThat(order.getFailureReason()).isEqualTo("insufficient balance");
    }

    @Test
    @DisplayName("终态冲突吸收：FAILED 后迟到 succeed 不得覆盖")
    void terminalFailedAbsorbsConflictingSuccess() {
        ChannelOrder order = fresh();
        order.fail("insufficient balance");

        boolean changed = order.succeed();

        assertThat(changed).isFalse();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.FAILED);
        assertThat(order.getFailureReason()).isEqualTo("insufficient balance");
    }

    // ===================== UNKNOWN（INV-005：不猜结果） =====================

    @Test
    @DisplayName("PENDING → markUnknown：超时/无响应进 UNKNOWN 并记原因")
    void pendingMarksUnknown() {
        ChannelOrder order = fresh();

        boolean changed = order.markUnknown("gateway timeout");

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.UNKNOWN);
        assertThat(order.getFailureReason()).isEqualTo("gateway timeout");
    }

    @Test
    @DisplayName("ACCEPTED → markUnknown：受理后无权威结果同样进 UNKNOWN")
    void acceptedMarksUnknown() {
        ChannelOrder order = fresh();
        order.accept("ch-ref-1");

        boolean changed = order.markUnknown("no final response");

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN → fail：收敛回权威失败")
    void unknownConvergesToFailed() {
        ChannelOrder order = fresh();
        order.markUnknown("timeout");

        boolean changed = order.fail("confirmed failure");

        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.FAILED);
        assertThat(order.getFailureReason()).isEqualTo("confirmed failure");
    }

    @Test
    @DisplayName("幂等：UNKNOWN 重复 markUnknown 被吸收")
    void duplicateMarkUnknownIsAbsorbed() {
        ChannelOrder order = fresh();
        order.markUnknown("first timeout");

        boolean changed = order.markUnknown("second timeout");

        assertThat(changed).isFalse();
        assertThat(order.getFailureReason()).isEqualTo("first timeout");
    }

    @Test
    @DisplayName("终态不被未知结果污染：SUCCEEDED 后 markUnknown 返回 false")
    void terminalSucceededIgnoresLateUnknown() {
        ChannelOrder order = fresh();
        order.succeed();

        assertThat(order.markUnknown("late timeout")).isFalse();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("终态不被未知结果污染：FAILED 后 markUnknown 返回 false")
    void terminalFailedIgnoresLateUnknown() {
        ChannelOrder order = fresh();
        order.fail("confirmed failure");

        assertThat(order.markUnknown("late timeout")).isFalse();
        assertThat(order.getStatus()).isEqualTo(ChannelOrderStatus.FAILED);
    }

    // ===================== 引用回填（收敛回退路径） =====================

    @Test
    @DisplayName("回填：受理时未返回引用 ⇒ PENDING/ACCEPTED/UNKNOWN 可补齐，终态不回填")
    void backfillOnlyForInFlightOrders() {
        ChannelOrder pending = fresh();
        assertThat(pending.backfillChannelReference("ch-backfill")).isTrue();
        assertThat(pending.getChannelReference()).isEqualTo("ch-backfill");

        ChannelOrder accepted = fresh();
        accepted.accept(null);
        assertThat(accepted.backfillChannelReference("ch-backfill")).isTrue();

        ChannelOrder unknown = fresh();
        unknown.markUnknown("timeout");
        assertThat(unknown.backfillChannelReference("ch-backfill")).isTrue();

        ChannelOrder succeeded = fresh();
        succeeded.succeed();
        assertThat(succeeded.backfillChannelReference("ch-backfill")).isFalse();
        assertThat(succeeded.getChannelReference()).isNull();

        ChannelOrder failed = fresh();
        failed.fail("confirmed failure");
        assertThat(failed.backfillChannelReference("ch-backfill")).isFalse();
    }

    @Test
    @DisplayName("回填：已有引用或入参为 null ⇒ 不覆盖（幂等）")
    void backfillNeverOverwrites() {
        ChannelOrder order = fresh();
        order.accept("ch-original");

        assertThat(order.backfillChannelReference("ch-other")).isFalse();
        assertThat(order.backfillChannelReference(null)).isFalse();
        assertThat(order.getChannelReference()).isEqualTo("ch-original");
    }

    // ===================== 结构收口：状态只能经状态机方法推进（INV-002） =====================

    @Test
    @DisplayName("INV-002：不得存在公共 setStatus —— 状态只由领域方法推进")
    void noPublicStatusSetter() {
        boolean hasSetter = Arrays.stream(ChannelOrder.class.getMethods())
                .map(Method::getName)
                .anyMatch("setStatus"::equals);
        assertThat(hasSetter)
                .as("对外暴露 setStatus 即绕开状态机，INV-002 形同虚设")
                .isFalse();
    }

    @Test
    @DisplayName("INV-002：status 初值为 PENDING（新渠道交互从未收敛开始）")
    void newOrderStartsPending() {
        assertThat(fresh().getStatus()).isEqualTo(ChannelOrderStatus.PENDING);
    }
}
