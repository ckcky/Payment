package com.payment.payment.limit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.infra.LimitProperties;
import com.payment.payment.limit.support.InMemoryLimitRepositories;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结算语义单测（spec 027 / FR-013~017、INV-4、INV-7、D12 软超限）。
 *
 * <p>核心：CONFIRM 把 pending 转成 used（不重复累加）、RELEASE/EXPIRED 只减 pending、
 * 同一流水重跑幂等跳过，以及软超限<b>如实累加不 clamp</b>。</p>
 */
class LimitSettlementServiceTest {

    private static final String USER = "user-1";
    private static final String CNY = "CNY";
    private static final String PM = "PM-1";

    private InMemoryLimitRepositories.Limits limits;
    private InMemoryLimitRepositories.Usages usages;
    private InMemoryLimitRepositories.Operations ops;
    private LimitProperties properties;
    private LimitSettlementService service;

    @BeforeEach
    void setUp() {
        limits = new InMemoryLimitRepositories.Limits();
        usages = new InMemoryLimitRepositories.Usages();
        ops = new InMemoryLimitRepositories.Operations();
        properties = new LimitProperties();
        properties.setEnabled(true);
        properties.setReserveTtl(Duration.ofSeconds(900));
        service = new LimitSettlementService(limits, usages, ops, new NoopLimitExpiryIndex(),
                properties, new NoopBusinessMetrics(), new StructuredAuditLogger());
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
    }

    @Test
    @DisplayName("CONFIRM：used 增加、pending 归零，落一条 CONFIRM 流水（FR-013）")
    void confirmMovesPendingToUsed() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 3_000L);

        assertThat(service.confirm(USER, CNY, 3_000L, PM)).isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY)).hasValueSatisfying(u -> {
            assertThat(u.usedMinor()).isEqualTo(3_000L);
            assertThat(u.pendingMinor()).isZero();
        });
        assertThat(ops.exists(PM, LimitOperationType.CONFIRM, null)).isTrue();
    }

    @Test
    @DisplayName("CONFIRM 重放：撞 UK 跳过金额变更，used 不重复累加（INV-4 关键）")
    void confirmIsIdempotent() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 3_000L);

        assertThat(service.confirm(USER, CNY, 3_000L, PM)).isTrue();
        assertThat(service.confirm(USER, CNY, 3_000L, PM))
                .as("第二次应幂等跳过").isFalse();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.usedMinor())
                        .as("重放不得二次累加").isEqualTo(3_000L));
        assertThat(ops.findByBizNo(PM).stream()
                .filter(o -> o.opType() == LimitOperationType.CONFIRM)).hasSize(1);
    }

    @Test
    @DisplayName("RELEASE：只减 pending，used 不受影响（FR-014，FAILED/CLOSED）")
    void releaseOnlyDecrementsPending() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 5_000L, 3_000L);

        assertThat(service.release(USER, CNY, 3_000L, PM)).isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY)).hasValueSatisfying(u -> {
            assertThat(u.usedMinor()).as("已确认金额不因释放而回退").isEqualTo(5_000L);
            assertThat(u.pendingMinor()).isZero();
        });
    }

    @Test
    @DisplayName("RELEASE 下限保护：pending 不足时减到 0 而非负数（INV-7）")
    void releaseNeverGoesNegative() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 1_000L);

        assertThat(service.release(USER, CNY, 5_000L, PM)).isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor())
                        .as("pending 不得为负").isZero());
    }

    @Test
    @DisplayName("EXPIRED：金额效果同 RELEASE，但流水类型可区分（FR-038 / D11）")
    void expireIsSemanticallyDistinctFromRelease() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 2_000L);

        assertThat(service.expire(USER, CNY, 2_000L, PM)).isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isZero());
        assertThat(ops.exists(PM, LimitOperationType.EXPIRED, null)).isTrue();
        assertThat(ops.exists(PM, LimitOperationType.RELEASE, null))
                .as("过期不等于渠道失败——流水类型必须区分").isFalse();
    }

    @Test
    @DisplayName("软超限（D12）：used 超过限额也如实累加，不 clamp、不拒绝（FR-039）")
    void overrunIsRecordedHonestly() {
        // 场景：TTL 已释放后支付才成功；这里直接以 used 已满 + 一笔确认模拟
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 10_000L, 5_000L);

        assertThat(service.confirm(USER, CNY, 5_000L, PM))
                .as("超限的确认必须成功（事实不可回滚）").isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY)).hasValueSatisfying(u -> {
            assertThat(u.usedMinor()).as("如实累加（不 clamp 到 limit）").isEqualTo(15_000L);
            assertThat(u.pendingMinor()).isZero();
            assertThat(u.overrunMinor(10_000L)).as("overrun 可见").isEqualTo(5_000L);
        });
    }

    @Test
    @DisplayName("软超限下 available 为负：不 clamp，保住「超了多少」信息（D12）")
    void availableGoesNegativeOnOverrun() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 12_000L, 0L);

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.availableMinor(10_000L)).isEqualTo(-2_000L));
    }

    @Test
    @DisplayName("无配置：结算直接跳过（与预占跳过对称，不存在「预占了却无配置可结」）")
    void settlementSkipsWhenNoConfiguration() {
        limits.delete(USER, CNY);
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 3_000L);

        assertThat(service.confirm(USER, CNY, 3_000L, PM)).isFalse();
        assertThat(ops.all()).isEmpty();
    }

    @Test
    @DisplayName("开关关闭：结算跳过（FR-024 一键回退）")
    void settlementSkipsWhenDisabled() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 3_000L);
        properties.setEnabled(false);

        assertThat(service.confirm(USER, CNY, 3_000L, PM)).isFalse();
    }

    @Test
    @DisplayName("isSettled：任一终态流水存在即为已结算（补偿扫描判定依据）")
    void isSettledCoversAllTerminalTypes() {
        assertThat(service.isSettled(PM)).isFalse();
        ops.insert(operation(PM, LimitOperationType.RESERVE));
        assertThat(service.isSettled(PM)).as("仅 RESERVE 不算已结算").isFalse();

        ops.insert(operation(PM, LimitOperationType.CONFIRM));
        assertThat(service.isSettled(PM)).isTrue();
    }

    @Test
    @DisplayName("settleByPayment：succeeded 走 CONFIRM、失败走 RELEASE（补偿扫描入口）")
    void settleByPaymentRoutesByOutcome() {
        usages.seed(USER, CNY, LimitPeriod.DAY, today(), 0L, 1_000L);

        assertThat(service.settleByPayment(PM, USER, CNY, 1_000L, true)).isTrue();
        assertThat(ops.exists(PM, LimitOperationType.CONFIRM, null)).isTrue();
    }

    @Test
    @DisplayName("三周期同时结算：受限的每个周期都归位")
    void settlementCoversAllLimitedPeriods() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 50_000L, 500_000L));
        for (LimitPeriod p : LimitPeriod.values()) {
            usages.seed(USER, CNY, p, periodStart(p), 0L, 2_000L);
        }

        assertThat(service.confirm(USER, CNY, 2_000L, PM)).isTrue();

        for (LimitPeriod p : LimitPeriod.values()) {
            assertThat(usages.find(USER, CNY, p)).as("周期 %s", p).hasValueSatisfying(u -> {
                assertThat(u.usedMinor()).isEqualTo(2_000L);
                assertThat(u.pendingMinor()).isZero();
            });
        }
    }

    private static com.payment.payment.limit.domain.LimitOperation operation(
            String paymentNo, LimitOperationType type) {
        return new com.payment.payment.limit.domain.LimitOperation(null, "LO-x", paymentNo, type,
                USER, CNY, LimitPeriod.DAY, 100L, null, Instant.now());
    }

    private static long today() {
        return LimitPeriod.DAY.periodStart(Instant.now()).toEpochDay();
    }

    private static long periodStart(LimitPeriod period) {
        return period.periodStart(Instant.now()).toEpochDay();
    }
}
