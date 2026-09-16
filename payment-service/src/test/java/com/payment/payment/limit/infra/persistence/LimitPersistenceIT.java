package com.payment.payment.limit.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.payment.limit.domain.LimitOperation;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.LimitUsage;
import com.payment.payment.limit.domain.LimitUsageRepository;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.domain.UserPaymentLimitRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限额持久层集成测试（H2 / MySQL 兼容模式）：验证<b>真实 SQL</b>的原子语义。
 *
 * <p>与 {@code LimitReserveServiceTest} 的分工：那边用内存仓储压<b>业务逻辑</b>（快、可并发）；
 * 本类压<b>SQL 本身</b>——{@code ON DUPLICATE KEY UPDATE} 建行、{@code GREATEST} 下限保护、
 * {@code WHERE used + pending + a <= limit} 的 0 行语义、以及 UK 撞键抛 {@code DuplicateKeyException}。</p>
 *
 * <p><b>UK 必须含 {@code period}</b>：本类第一条测试就是它的守门人——DAY 与 MONTH 的同
 * {@code biz_no} / {@code op_type} 流水必须能同时存在。若 UK 退回 {@code (biz_no, op_type)}，
 * 这条会立刻红。</p>
 *
 * <p><b>隔离靠 {@code @Transactional} 回滚</b>：H2 是上下文级共享的内存库，若各用例用同一
 * 业务单号却不回滚，前一个用例留下的流水会让后一个用例的首次插入撞 UK（假失败）。
 * 回滚也顺带保证本类不污染其他集成测试。</p>
 */
@SpringBootTest
@Transactional
class LimitPersistenceIT {

    private static final String USER = "limit-it-user";
    private static final String CNY = "CNY";

    @Autowired
    private UserPaymentLimitRepository limitRepository;

    @Autowired
    private LimitUsageRepository usageRepository;

    @Autowired
    private LimitOperationRepository operationRepository;

    /** 直连 mapper：仅用于验证 SQL 层安全阀参数（periodStart 不在领域接口上暴露）。 */
    @Autowired
    private UserLimitUsageMapper usageMapper;

    /** 每个用例独立的业务单号，避免同上下文内互相撞 UK。 */
    private String bizNo;

    @BeforeEach
    void setUp() {
        // 唯一业务单号 + 事务回滚：双重隔离，避免上下文共享带来的假失败
        bizNo = "PM-it-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        limitRepository.delete(USER, CNY);
    }

    @Test
    @DisplayName("UK(biz_no, op_type, period)：同单号同类型流水可按周期并存（三周期模型的地基）")
    void uniqueKeyIncludesPeriodSoAllPeriodsCanCoexist() {
        // 这条是整个三周期模型的守门人：若 UK 少了 period，第二、三条会撞键返回 false，
        // 月/年周期将永远无法预占（限额静默失效）。
        for (LimitPeriod period : LimitPeriod.values()) {
            boolean inserted = operationRepository.insert(operation(period, LimitOperationType.RESERVE));
            assertThat(inserted).as("周期 %s 的 RESERVE 应可独立插入", period).isTrue();
        }

        assertThat(operationRepository.findByBizNo(bizNo)).hasSize(3);
        assertThat(operationRepository.find(bizNo, LimitOperationType.RESERVE, LimitPeriod.DAY))
                .isPresent();
        assertThat(operationRepository.exists(bizNo, LimitOperationType.RESERVE, null)).isTrue();
        assertThat(operationRepository.exists(bizNo, LimitOperationType.RESERVE, LimitPeriod.MONTH))
                .isTrue();
        assertThat(operationRepository.exists(bizNo, LimitOperationType.CONFIRM, null))
                .isFalse();
    }

    @Test
    @DisplayName("同周期同类型重复插入：撞 UK 返回 false（幂等闸门生效于数据库层，INV-4）")
    void duplicateSamePeriodInsertReturnsFalse() {
        assertThat(operationRepository.insert(operation(LimitPeriod.DAY, LimitOperationType.RESERVE)))
                .isTrue();
        assertThat(operationRepository.insert(operation(LimitPeriod.DAY, LimitOperationType.RESERVE)))
                .as("同 (bizNo, opType, period) 第二次插入必须返回 false").isFalse();
        assertThat(operationRepository.findByBizNo(bizNo)).hasSize(1);
    }

    @Test
    @DisplayName("原子预占：额度内成功、超限 0 行且金额不变（FR-006 / FR-008）")
    void atomicReserveHonoursLimit() {
        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, today());

        assertThat(usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 3_000L, 10_000L))
                .as("额度内应影响 1 行").isEqualTo(1);
        assertThat(usageRepository.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(3_000L));

        // 再占 8_000：3000 + 8000 > 10000 → 0 行
        assertThat(usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 8_000L, 10_000L))
                .as("超限应影响 0 行").isZero();
        assertThat(usageRepository.find(USER, CNY, LimitPeriod.DAY))
                .as("失败的预占不得改动任何金额")
                .hasValueSatisfying(u -> assertThat(u.pendingMinor()).isEqualTo(3_000L));

        // 边界相等：3000 + 7000 == 10000 → 放行
        assertThat(usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 7_000L, 10_000L))
                .as("恰好等于额度应放行（<=）").isEqualTo(1);
    }

    @Test
    @DisplayName("跨周期安全阀：period_start 不匹配时 0 行，绝不在旧周期行上累加")
    void stalePeriodStartDoesNotAccumulate() {
        LocalDate yesterday = today().minusDays(1);
        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, yesterday);

        // 直接走 mapper：periodStart 是 SQL 层的安全阀参数，不在领域仓储接口上暴露
        int affected = usageMapper.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY.name(),
                today(), 1_000L, 10_000L);

        assertThat(affected).as("旧周期行不应被当前的预占命中").isZero();
    }

    @Test
    @DisplayName("CONFIRM 无条件累加：used 累加、pending 用 GREATEST 归零（D12 / INV-7）")
    void confirmAccumulatesAndClampsPending() {
        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, today());
        usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 3_000L, 10_000L);

        assertThat(usageRepository.confirm(USER, CNY, LimitPeriod.DAY, 3_000L)).isEqualTo(1);

        assertThat(usageRepository.find(USER, CNY, LimitPeriod.DAY)).hasValueSatisfying(u -> {
            assertThat(u.usedMinor()).isEqualTo(3_000L);
            assertThat(u.pendingMinor()).isZero();
        });
    }

    @Test
    @DisplayName("RELEASE 下限保护：GREATEST 让 pending 减到 0 而非负数（INV-7）")
    void releaseClampsAtZero() {
        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, today());
        usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 1_000L, 10_000L);

        // 释放 5_000 > pending 1_000
        assertThat(usageRepository.release(USER, CNY, LimitPeriod.DAY, 5_000L)).isEqualTo(1);

        assertThat(usageRepository.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor())
                        .as("pending 不得为负").isZero());
    }

    @Test
    @DisplayName("ensureRow 幂等：重复调用不覆盖既有占用（并发建行安全）")
    void ensureRowIsIdempotent() {
        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, today());
        usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 2_500L, 10_000L);

        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, today());

        assertThat(usageRepository.find(USER, CNY, LimitPeriod.DAY))
                .hasValueSatisfying(u -> assertThat(u.pendingMinor())
                        .as("重复建行不得清零既有占用").isEqualTo(2_500L));
    }

    @Test
    @DisplayName("限额配置 upsert 幂等 + delete 回归不限额（FR-020 / FR-025 / SC-010）")
    void limitConfigurationUpsertThenDelete() {
        limitRepository.upsert(UserPaymentLimit.of(USER, CNY, 10_000L, 0L, 0L));
        assertThat(limitRepository.find(USER, CNY))
                .hasValueSatisfying(l -> assertThat(l.dailyLimitMinor()).isEqualTo(10_000L));

        limitRepository.upsert(UserPaymentLimit.of(USER, CNY, 20_000L, 0L, 0L));
        assertThat(limitRepository.find(USER, CNY))
                .as("upsert 应更新而非插入第二行")
                .hasValueSatisfying(l -> assertThat(l.dailyLimitMinor()).isEqualTo(20_000L));

        assertThat(limitRepository.delete(USER, CNY)).isTrue();
        assertThat(limitRepository.find(USER, CNY)).as("删除后 = 不限额").isEmpty();
        assertThat(limitRepository.delete(USER, CNY)).as("重复删除返回 false").isFalse();
    }

    @Test
    @DisplayName("findUnsettledReserves：只有 RESERVE 的在途被找出，终态后不再命中（FR-038）")
    void unsettledReservesExcludesSettled() {
        String unsettled = "PM-it-unsettled";
        String settled = "PM-it-settled";
        for (LimitPeriod p : LimitPeriod.values()) {
            operationRepository.insert(new LimitOperation(null, "LO-u-" + p, unsettled,
                    LimitOperationType.RESERVE, USER, CNY, p, 100L, Instant.now(), Instant.now()));
            operationRepository.insert(new LimitOperation(null, "LO-s-" + p, settled,
                    LimitOperationType.RESERVE, USER, CNY, p, 100L, Instant.now(), Instant.now()));
        }
        operationRepository.insert(new LimitOperation(null, "LO-sc", settled,
                LimitOperationType.CONFIRM, USER, CNY, LimitPeriod.DAY, 100L, null, Instant.now()));

        assertThat(operationRepository.findUnsettledReserves(USER))
                .extracting(LimitOperation::bizNo)
                .containsOnly(unsettled);
    }

    @Test
    @DisplayName("惰性回收扫描粒度：全未结算时返回三周期三条；任一终态后整单不再返回（FR-038）")
    void unsettledReservesScopeIsPerPeriodThenWholePayment() {
        for (LimitPeriod p : LimitPeriod.values()) {
            operationRepository.insert(operation(p, LimitOperationType.RESERVE));
        }

        // 三个周期各自的 pending 都要被释放，故回收必须看到三条（每周期一条）
        assertThat(operationRepository.findUnsettledReserves(USER))
                .as("全未结算：三周期三条都要被回收处理")
                .hasSize(3)
                .allMatch(op -> op.bizNo().equals(bizNo));

        // 只结算了一个周期：与 spec 的「自愈性」口径一致——不再由回收处理，
        // 剩余周期交由补偿扫描（以 payment 终态为事实源）收敛。
        operationRepository.insert(new LimitOperation(null, "LO-confirm-day", bizNo,
                LimitOperationType.CONFIRM, USER, CNY, LimitPeriod.DAY, 100L, null, Instant.now()));

        assertThat(operationRepository.findUnsettledReserves(USER))
                .as("已有任一终态流水 → 整单不再由惰性回收处理")
                .isEmpty();
    }

    @Test
    @DisplayName("删除单周期流水：只删指定周期，其他周期保留（预占回滚的精确性）")
    void deleteRemovesOnlyTargetPeriod() {        for (LimitPeriod p : LimitPeriod.values()) {
            operationRepository.insert(operation(p, LimitOperationType.RESERVE));
        }

        LimitOperation dayOp = operationRepository
                .find(bizNo, LimitOperationType.RESERVE, LimitPeriod.DAY)
                .orElseThrow();
        operationRepository.delete(dayOp);

        assertThat(operationRepository.find(bizNo, LimitOperationType.RESERVE, LimitPeriod.DAY))
                .as("DAY 流水应被删除").isEmpty();
        assertThat(operationRepository.find(bizNo, LimitOperationType.RESERVE, LimitPeriod.MONTH))
                .as("MONTH 流水不得被误删").isPresent();
        assertThat(operationRepository.find(bizNo, LimitOperationType.RESERVE, LimitPeriod.YEAR))
                .as("YEAR 流水不得被误删").isPresent();
    }

    @Test
    @DisplayName("占用读模型：available 允许为负、overrun 反映真实超出（D12 软超限）")
    void usageReadModelExposesOverrun() {
        usageRepository.ensureRow(USER, CNY, LimitPeriod.DAY, today());
        usageRepository.reserveIfWithinLimit(USER, CNY, LimitPeriod.DAY, 9_000L, 10_000L);
        usageRepository.confirm(USER, CNY, LimitPeriod.DAY, 9_000L);
        // 再确认一笔（模拟 TTL 释放后支付才成功）→ used 超过限额
        usageRepository.confirm(USER, CNY, LimitPeriod.DAY, 3_000L);

        LimitUsage usage = usageRepository.find(USER, CNY, LimitPeriod.DAY).orElseThrow();
        assertThat(usage.usedMinor()).isEqualTo(12_000L);
        assertThat(usage.availableMinor(10_000L)).as("允许为负（可见的超限）").isEqualTo(-2_000L);
        assertThat(usage.overrunMinor(10_000L)).isEqualTo(2_000L);
    }

    private static LocalDate today() {
        return LimitPeriod.DAY.periodStart(Instant.now());
    }

    private LimitOperation operation(LimitPeriod period, LimitOperationType type) {
        return new LimitOperation(null, "LO-" + period + "-" + type, bizNo, type,
                USER, CNY, period, 100L, Instant.now(), Instant.now());
    }
}
