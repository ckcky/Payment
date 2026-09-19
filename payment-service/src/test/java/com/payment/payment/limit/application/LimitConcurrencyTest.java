package com.payment.payment.limit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.limit.domain.LimitExceededException;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.infra.LimitProperties;
import com.payment.payment.limit.support.InMemoryLimitRepositories;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发与补偿语义测试（spec 027 / INV-3、INV-4、INV-7、FR-038、SC-005）。
 *
 * <p>限额域最危险的不是单笔逻辑，而是<b>并发穿透</b>——N 笔同日请求同时读到「未超限」
 * 再全部放行。本类用真实多线程压这一点：判定若走「读-改-写」而非原子 UPDATE，
 * 本类必然失败。</p>
 */
class LimitConcurrencyTest {

    private static final String CNY = "CNY";

    private InMemoryLimitRepositories.Limits limits;
    private InMemoryLimitRepositories.Usages usages;
    private InMemoryLimitRepositories.Operations ops;
    private LimitProperties properties;
    private LimitReserveService reserveService;
    private LimitSettlementService settlementService;

    @BeforeEach
    void setUp() {
        limits = new InMemoryLimitRepositories.Limits();
        usages = new InMemoryLimitRepositories.Usages();
        ops = new InMemoryLimitRepositories.Operations();
        properties = new LimitProperties();
        properties.setEnabled(true);
        properties.setReserveTtl(Duration.ofSeconds(900));
        reserveService = new LimitReserveService(limits, usages, ops, new NoopLimitExpiryIndex(),
                properties, new NoopBusinessMetrics());
        settlementService = new LimitSettlementService(limits, usages, ops,
                new NoopLimitExpiryIndex(), properties, new NoopBusinessMetrics(),
                new StructuredAuditLogger());
    }

    @Test
    @DisplayName("并发预占不可穿透：20 笔 1000 分争 5000 分日额度 → 恰好 5 笔成功（INV-3 / SC-005）")
    void concurrentReserveCannotExceedLimit() throws Exception {
        final long limit = 5_000L;
        final long each = 1_000L;
        final int threads = 20;
        limits.upsert(UserPaymentLimit.of("user-c", CNY, limit, 0L, 0L));

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(threads, i -> {
            try {
                if (reserveService.reserve("user-c", CNY, each, "PM-" + i)) {
                    succeeded.incrementAndGet();
                }
            } catch (LimitExceededException e) {
                rejected.incrementAndGet();
            }
            return null;
        });

        assertThat(succeeded.get()).as("恰好放行 floor(limit/each) 笔").isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(15);
        assertThat(usages.find("user-c", CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor())
                        .as("占用恰好等于额度，不得超发").isEqualTo(limit));
    }

    @Test
    @DisplayName("并发确认幂等：同一 paymentNo 并发 CONFIRM 只累加一次（INV-4 / SC-006）")
    void concurrentConfirmIsIdempotent() throws Exception {
        limits.upsert(UserPaymentLimit.of("user-d", CNY, 100_000L, 0L, 0L));
        usages.seed("user-d", CNY, LimitPeriod.DAY, today(), 0L, 3_000L);

        AtomicInteger settled = new AtomicInteger();
        runConcurrently(10, i -> {
            if (settlementService.confirm("user-d", CNY, 3_000L, "PM-concurrent")) {
                settled.incrementAndGet();
            }
            return null;
        });

        assertThat(settled.get()).as("只有一次真正结算").isEqualTo(1);
        assertThat(usages.find("user-d", CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.usedMinor())
                        .as("重放不得二次累加").isEqualTo(3_000L));
    }

    @Test
    @DisplayName("并发预占 + 释放交错：pending 不出现负数，最终归零（INV-7）")
    void concurrentReserveAndReleaseKeepsPendingNonNegative() throws Exception {
        limits.upsert(UserPaymentLimit.of("user-e", CNY, 100_000L, 0L, 0L));
        String pm = "PM-e";

        // 先预占一次（单线程，确定成功）
        assertThat(reserveService.reserve("user-e", CNY, 3_000L, pm)).isTrue();

        // 并发 10 次 RELEASE：只有一条 RELEASE 流水生效（幂等），其余跳过
        AtomicInteger released = new AtomicInteger();
        runConcurrently(10, i -> {
            if (settlementService.release("user-e", CNY, 3_000L, pm)) {
                released.incrementAndGet();
            }
            return null;
        });

        assertThat(released.get()).isEqualTo(1);
        assertThat(usages.find("user-e", CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isZero());
    }

    @Test
    @DisplayName("不同单号并发：各自独立流水，都成功（不互相幂等抵消）")
    void concurrentDistinctPaymentNosAllSucceed() throws Exception {
        limits.upsert(UserPaymentLimit.of("user-f", CNY, 100_000L, 0L, 0L));

        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(10, i -> {
            if (reserveService.reserve("user-f", CNY, 1_000L, "PM-f-" + i)) {
                succeeded.incrementAndGet();
            }
            return null;
        });

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(usages.find("user-f", CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(10_000L));
        assertThat(ops.all().stream().filter(o -> o.opType() == LimitOperationType.RESERVE))
                .hasSize(10);
    }

    @Test
    @DisplayName("补偿扫描：只有 RESERVE 的在途被找出，已结算的不再重复处理（FR-038）")
    void compensationFindsOnlyUnsettledReserves() {
        limits.upsert(UserPaymentLimit.of("user-g", CNY, 100_000L, 0L, 0L));
        reserveService.reserve("user-g", CNY, 1_000L, "PM-g-1");
        reserveService.reserve("user-g", CNY, 2_000L, "PM-g-2");
        settlementService.confirm("user-g", CNY, 2_000L, "PM-g-2");

        List<com.payment.payment.limit.domain.LimitOperation> unsettled =
                ops.findUnsettledReserves("user-g");

        assertThat(unsettled).extracting(com.payment.payment.limit.domain.LimitOperation::bizNo)
                .containsExactly("PM-g-1");
    }

    @Test
    @DisplayName("补偿收敛：对未结算在途补结算后，占用正确归位且幂等（SC-007）")
    void compensationSettlesAndIsIdempotent() {
        limits.upsert(UserPaymentLimit.of("user-h", CNY, 100_000L, 0L, 0L));
        reserveService.reserve("user-h", CNY, 4_000L, "PM-h");
        assertThat(usages.find("user-h", CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(4_000L));

        // 模拟支付已 SUCCEEDED 但结算崩溃 → 补偿补齐
        assertThat(settlementService.settleByPayment("PM-h", "user-h", CNY, 4_000L, true)).isTrue();
        // 再跑一次补偿（扫描重跑）→ 幂等跳过
        assertThat(settlementService.settleByPayment("PM-h", "user-h", CNY, 4_000L, true)).isFalse();

        assertThat(usages.find("user-h", CNY, LimitPeriod.DAY)).hasValueSatisfying(u -> {
            assertThat(u.usedMinor()).isEqualTo(4_000L);
            assertThat(u.pendingMinor()).isZero();
        });
    }

    /** 三周期一起并发：日额度是短板时，月/年周期不得被部分扣减（FR-010 并发版）。 */
    @Test
    @DisplayName("并发下三周期一致性：失败的请求在所有周期都不留残留（FR-010）")
    void failedConcurrentReservesLeaveNoResidueInAnyPeriod() throws Exception {
        limits.upsert(UserPaymentLimit.of("user-i", CNY, 3_000L, 100_000L, 1_000_000L));

        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(12, i -> {
            try {
                reserveService.reserve("user-i", CNY, 1_000L, "PM-i-" + i);
                succeeded.incrementAndGet();
            } catch (LimitExceededException ignored) {
                // 预期：日额度只够 3 笔
            }
            return null;
        });

        assertThat(succeeded.get()).isEqualTo(3);
        for (LimitPeriod p : LimitPeriod.values()) {
            assertThat(usages.find("user-i", CNY, p))
                    .as("周期 %s 的 pending 必须等于成功笔数 × 金额", p)
                    .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(3_000L));
        }
        // 每笔成功支付在三个周期上各留一条 RESERVE 流水 → 3 笔 × 3 周期 = 9 条。
        // 失败请求的流水必须被按周期精确清干净，故条数恰好等于「成功笔数 × 周期数」。
        assertThat(ops.all().stream().filter(o -> o.opType() == LimitOperationType.RESERVE))
                .as("失败的请求必须清掉自己的 RESERVE 流水")
                .hasSize(3 * LimitPeriod.values().length)
                .allSatisfy(op -> assertThat(op.bizNo()).startsWith("PM-i-"));
    }

    private static long today() {
        return LimitPeriod.DAY.periodStart(java.time.Instant.now()).toEpochDay();
    }

    /** 用栅栏让所有线程尽量同时开跑，最大化竞态窗口。 */
    private static void runConcurrently(int threads, TaskFn fn) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit((Callable<Object>) () -> {
                    ready.countDown();
                    start.await();
                    return fn.run(idx);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface TaskFn {
        Object run(int index) throws Exception;
    }
}
