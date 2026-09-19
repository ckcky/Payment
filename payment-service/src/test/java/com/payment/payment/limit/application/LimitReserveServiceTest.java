package com.payment.payment.limit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.payment.limit.domain.LimitExceededException;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.infra.LimitProperties;
import com.payment.payment.limit.support.InMemoryLimitRepositories;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预占语义单测（spec 027 / FR-006~012、INV-3、INV-4、INV-6、INV-7）。
 *
 * <p>本类压的是<b>判定路径</b>：三周期独立判定与「不得部分扣减」（FR-010）、
 * 「无配置 = 不限额」（FR-012）、同单号重放的幂等（INV-4）、
 * 以及从内存仓储的原子语义中透出的并发不可穿透性。</p>
 */
class LimitReserveServiceTest {

    private static final String USER = "user-1";
    private static final String CNY = "CNY";
    private static final String PM = "PM-1";

    private InMemoryLimitRepositories.Limits limits;
    private InMemoryLimitRepositories.Usages usages;
    private InMemoryLimitRepositories.Operations ops;
    private LimitProperties properties;
    private LimitReserveService service;

    @BeforeEach
    void setUp() {
        limits = new InMemoryLimitRepositories.Limits();
        usages = new InMemoryLimitRepositories.Usages();
        ops = new InMemoryLimitRepositories.Operations();
        properties = new LimitProperties();
        properties.setEnabled(true);
        properties.setReserveTtl(Duration.ofSeconds(900));
        service = new LimitReserveService(limits, usages, ops, new NoopLimitExpiryIndex(),
                properties, new NoopBusinessMetrics());
    }

    @Test
    @DisplayName("无配置行 = 不限额：预占直接放行且不产生任何占用行（FR-012 / FR-024）")
    void noConfigurationMeansUnlimited() {
        boolean reserved = service.reserve(USER, CNY, 1_000_000L, PM);

        assertThat(reserved).isFalse();
        assertThat(usages.findAll(USER, CNY)).isEmpty();
        assertThat(ops.all()).isEmpty();
    }

    @Test
    @DisplayName("三个额度全为 0 = 不限额：与无配置同义（FR-012）")
    void allZeroLimitsMeanUnlimited() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 0L, 0L, 0L));

        boolean reserved = service.reserve(USER, CNY, 999_999L, PM);

        assertThat(reserved).isFalse();
        assertThat(usages.findAll(USER, CNY)).isEmpty();
    }

    @Test
    @DisplayName("DISABLED 配置视同不限额：临时放行不必删配置（FR-003）")
    void disabledConfigurationMeansUnlimited() {
        limits.upsert(new UserPaymentLimit(null, USER, CNY, 100L, 0L, 0L,
                UserPaymentLimit.STATUS_DISABLED, null));

        assertThat(service.reserve(USER, CNY, 10_000L, PM)).isFalse();
        assertThat(usages.findAll(USER, CNY)).isEmpty();
    }

    @Test
    @DisplayName("开关关闭：整段跳过，幂等且零副作用（FR-024 一键回退）")
    void disabledSwitchSkipsEverything() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 100L, 0L, 0L));
        properties.setEnabled(false);

        assertThat(service.reserve(USER, CNY, 1_000L, PM)).isFalse();
        assertThat(usages.findAll(USER, CNY)).isEmpty();
        assertThat(ops.all()).isEmpty();
    }

    @Test
    @DisplayName("额度内预占成功：pending 增加，且落一条 RESERVE 流水（FR-006 / INV-4）")
    void reserveWithinLimitSucceeds() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));

        boolean reserved = service.reserve(USER, CNY, 3_000L, PM);

        assertThat(reserved).isTrue();
        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> {
                    assertThat(u.pendingMinor()).isEqualTo(3_000L);
                    assertThat(u.usedMinor()).isZero();
                });
        assertThat(ops.find(PM, LimitOperationType.RESERVE, null)).isPresent();
    }

    @Test
    @DisplayName("超限：抛 LIMIT_EXCEEDED，错误消息含周期/额度/缺口（FR-019，可执行错误）")
    void exceedingLimitThrowsWithActionableMessage() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
        usages.seed(USER, CNY, LimitPeriod.DAY,
                LimitPeriod.DAY.periodStart(java.time.Instant.now()).toEpochDay(), 9_000L, 0L);

        assertThatThrownBy(() -> service.reserve(USER, CNY, 2_000L, PM))
                .isInstanceOf(LimitExceededException.class)
                .hasMessageContaining("DAY")
                .hasMessageContaining("limit=10000")
                .hasMessageContaining("requested=2000")
                .hasMessageContaining("shortfall=1000")
                .hasMessageContaining("the payment was NOT created");
    }

    @Test
    @DisplayName("边界相等：occupied + a == limit 恰好放行（<= 而非 <）")
    void exactlyAtLimitIsAllowed() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
        usages.seed(USER, CNY, LimitPeriod.DAY,
                LimitPeriod.DAY.periodStart(java.time.Instant.now()).toEpochDay(), 9_000L, 0L);

        assertThat(service.reserve(USER, CNY, 1_000L, PM)).isTrue();
    }

    @Test
    @DisplayName("三周期任一超限即整笔拒绝：已预占的周期必须回滚，不得部分扣减（FR-010）")
    void anyPeriodExceededRollsBackAllPeriods() {
        // 日额度充足，月额度不够：DAY 先判（枚举序），判定通过后 MONTH 失败 →
        // DAY 的 pending 必须被释放，且 DAY 的 RESERVE 流水必须被清掉。
        limits.upsert(UserPaymentLimit.of(USER, CNY, 100_000L, 5_000L, 0L));
        usages.seed(USER, CNY, LimitPeriod.MONTH,
                LimitPeriod.MONTH.periodStart(java.time.Instant.now()).toEpochDay(), 4_000L, 0L);

        assertThatThrownBy(() -> service.reserve(USER, CNY, 2_000L, PM))
                .isInstanceOf(LimitExceededException.class);

        // 不得部分扣减：DAY 的 pending 回到 0
        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isZero());
        // 失败的 RESERVE 流水必须清掉，否则同单号重试会被幂等闸门误判为「已预占」而放行
        assertThat(ops.find(PM, LimitOperationType.RESERVE, null)).isEmpty();
    }

    @Test
    @DisplayName("同单号重放：第二条 RESERVE 撞 UK 视为成功但金额不重复累加（INV-4）")
    void duplicateReserveIsIdempotent() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));

        assertThat(service.reserve(USER, CNY, 3_000L, PM)).isTrue();
        // 同一 paymentNo 再次预占（重放/补偿）
        assertThat(service.reserve(USER, CNY, 3_000L, PM)).isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor())
                        .as("重复预占不得叠加金额").isEqualTo(3_000L));
        assertThat(ops.findByBizNo(PM)).hasSize(1);
    }

    @Test
    @DisplayName("三个周期同时受限：三行都被预占（都通过时）")
    void allThreePeriodsReservedWhenAllPass() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 50_000L, 500_000L));

        assertThat(service.reserve(USER, CNY, 3_000L, PM)).isTrue();

        for (LimitPeriod p : LimitPeriod.values()) {
            assertThat(usages.find(USER, CNY, p))
                    .as("周期 %s 应被预占", p)
                    .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(3_000L));
        }
    }

    @Test
    @DisplayName("超限时不留下任何占用：已预占周期被释放，未触及周期不建行")
    void failedReserveLeavesNoResidue() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 50_000L, 500_000L));
        usages.seed(USER, CNY, LimitPeriod.MONTH,
                LimitPeriod.MONTH.periodStart(java.time.Instant.now()).toEpochDay(), 50_000L, 0L);

        assertThatThrownBy(() -> service.reserve(USER, CNY, 1_000L, PM))
                .isInstanceOf(LimitExceededException.class);

        // DAY 先判定通过、MONTH 失败 → DAY 的预占必须被释放（FR-010 不得部分扣减）
        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isZero());
        // YEAR 尚未被触及：判定在 MONTH 就中断了，不应留下任何行（更不该有占用）
        assertThat(usages.find(USER, CNY, LimitPeriod.YEAR)).isEmpty();
    }

    @Test
    @DisplayName("已确认 used 也计入占用：used 逼近额度时新单被拒（FR-005）")
    void usedAmountCountsTowardLimit() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
        usages.seed(USER, CNY, LimitPeriod.DAY,
                LimitPeriod.DAY.periodStart(java.time.Instant.now()).toEpochDay(), 10_000L, 0L);

        assertThatThrownBy(() -> service.reserve(USER, CNY, 1L, PM))
                .isInstanceOf(LimitExceededException.class);
    }

    @Test
    @DisplayName("跨周期复位：旧 period_start 的行在现算周期下被重置（D10 无调度器）")
    void stalePeriodRowIsResetOnNewPeriod() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
        // 昨天已用满（旧周期行）
        usages.seed(USER, CNY, LimitPeriod.DAY,
                LimitPeriod.DAY.periodStart(java.time.Instant.now()).toEpochDay() - 1, 10_000L, 0L);

        // 新的一天：ensureRow 发现 period_start 不符 → 重置累计 → 应当放行
        assertThat(service.reserve(USER, CNY, 5_000L, PM)).isTrue();

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> {
                    assertThat(u.usedMinor()).as("旧周期累计必须被重置").isZero();
                    assertThat(u.pendingMinor()).isEqualTo(5_000L);
                });
    }

    @Test
    @DisplayName("预占金额取支付单金额（INV-6）：传多少就占多少，不读渠道回执")
    void reserveUsesPaymentAmount() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));

        service.reserve(USER, CNY, 7_777L, PM);

        assertThat(usages.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(7_777L));
        assertThat(ops.find(PM, LimitOperationType.RESERVE, null))
                .hasValueSatisfying(op -> assertThat(op.amountMinor()).isEqualTo(7_777L));
    }

    @Test
    @DisplayName("TTL 写入 Redis 过期索引：成功后标记存在（FR-036）")
    void reserveMarksExpiryIndex() {
        limits.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
        RecordingExpiryIndex index = new RecordingExpiryIndex();
        service = new LimitReserveService(limits, usages, ops, index, properties,
                new NoopBusinessMetrics());

        service.reserve(USER, CNY, 1_000L, PM);

        assertThat(index.marked).containsExactly(PM);
    }

    /** 记录式过期索引：断言 mark/clear 的调用，不依赖 Redis。 */
    private static final class RecordingExpiryIndex implements LimitExpiryIndex {
        private final java.util.List<String> marked = new java.util.ArrayList<>();
        private final java.util.List<String> cleared = new java.util.ArrayList<>();

        @Override
        public void mark(String paymentNo, long amountMinor, Duration ttl) {
            marked.add(paymentNo);
        }

        @Override
        public void clear(String paymentNo) {
            cleared.add(paymentNo);
        }

        @Override
        public java.util.Set<String> alive(java.util.Collection<String> paymentNos) {
            return new java.util.HashSet<>(marked);
        }
    }
}
