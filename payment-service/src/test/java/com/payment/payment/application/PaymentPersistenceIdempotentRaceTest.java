package com.payment.payment.application;

import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.InMemoryPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建单幂等的<b>并发分支</b>回归（FIX-2 / 2026-09-20 边界复审）。
 *
 * <p><b>被修的缺陷</b>：{@code PaymentPersistence.insertPending} 里，{@code insertNew} 捕获
 * {@link DuplicateKeyException} 后只回查了既有支付单，调用方却<b>继续</b>为这条已存在的支付单
 * {@code openPaymentAttempt} 建第二条 attempt、再 {@code payment.start(新 attemptId)}——
 * 而既有支付单此时已是 {@code PROCESSING}，于是 {@code start} 抛
 * {@code STATE_TRANSITION_VIOLATION}、整个建单事务回滚。
 * 结果：<b>幂等重试被答成状态机错误</b>（调用方拿到 409/500，而不是首次结果）。</p>
 *
 * <p><b>为什么必须专门造这个场景</b>：撞唯一键只在<b>真并发</b>（两个请求同时越过「先查」这步）
 * 时发生，而常规单测走的都是「先查命中 ⇒ 提前返回」那条路——两者返回的都是
 * {@code created=false}，但一条正确、一条会抛错。用「对手方先落库」的仓储桩把竞争钉成确定性，
 * 才能让这条分支进测试覆盖（否则它只在生产并发下暴露）。</p>
 *
 * <p>断言的口径是 FR-152 / FR-306：幂等重复命中已存在支付单时返回<b>库内</b>值——
 * 不新建 attempt、不推进状态机、不用本次请求的值覆盖库内事实。</p>
 */
class PaymentPersistenceIdempotentRaceTest {

    private static final String IDEMPOTENCY_KEY = "idem-race-1";

    /**
     * 模拟「并发对手方已抢先插入」的仓储：第一次 {@code save} 抛唯一键冲突，
     * 同时把对手方那条真实存在的行放进库——这正是 {@code insertPending} 撞键那一刻的库内状态。
     */
    private static final class RaceLosingRepository extends InMemoryPaymentRepository {

        private final Payment winner;
        private boolean raced;

        RaceLosingRepository(Payment winner) {
            this.winner = winner;
        }

        @Override
        public Payment save(Payment payment) {
            if (!raced) {
                raced = true;
                super.save(winner); // 对手方先提交：库内已有同幂等键的支付单
                throw new DuplicateKeyException("uk_payments_idempotency_key: " + IDEMPOTENCY_KEY);
            }
            return super.save(payment);
        }
    }

    @Test
    @DisplayName("并发撞幂等键 ⇒ 回放库内支付单与既有 attempt（不再建第二条 attempt、不推状态机）[FR-152][FR-306][FIX-2]")
    void concurrentDuplicateReplaysStoredPaymentInsteadOfViolatingStateMachine() {
        // 对手方那笔：已落库、已 PROCESSING、已有一条 PAYMENT attempt
        Payment winner = new Payment("txn-race", "order-race", "user-race", 100L, "CNY", IDEMPOTENCY_KEY);
        InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        PaymentAttempt stored = attempts.openPaymentAttempt(winner.getPaymentNo(), "MOCK", 100L, "CNY");
        winner.start(stored.getId()); // PENDING -> PROCESSING（并发下首次请求已完成这一步）

        PaymentPersistence persistence = new PaymentPersistence(new RaceLosingRepository(winner), attempts);

        PaymentPersistence.PendingPayment pending = persistence.insertPending(
                new CreatePaymentCommand("txn-race", "order-race", "user-race", 100L, "CNY",
                        IDEMPOTENCY_KEY, "MOCK"),
                "MOCK");

        assertThat(pending.created())
                .as("撞唯一键后必须是「命中重复」而非「新建」——修复前这里会一路走到 payment.start 抛状态机错误")
                .isFalse();
        assertThat(pending.payment()).isSameAs(winner);
        assertThat(pending.attempt())
                .as("幂等重放必须回放库内那条 attempt，而不是新建一条")
                .isSameAs(stored);
        assertThat(attempts.findByPaymentNo(winner.getPaymentNo()))
                .as("Payment 1:1 PaymentAttempt：重放 MUST NOT 产生第二条 PAYMENT 尝试行")
                .hasSize(1);
        assertThat(pending.payment().getStatus())
                .as("重放不得改动库内支付单状态")
                .isEqualTo(PaymentStatus.PROCESSING);
    }
}
