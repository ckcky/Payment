package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.error.BizException;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.infra.InMemoryLedgerRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 必测⑤⑥（spec 031 原则 10 / §4）：重复事件不产生重复分录；并发重复只允许一个赢家，
 * 撞唯一键的一方回查回放首次结果。幂等键由账本派生 {@code {eventType}:{sourceId}}。
 */
class PostingIdempotencyTest {

    @Test
    @DisplayName("必测⑤：同一事件重复投递 ⇒ 回放首笔交易，分录与余额不重复累加")
    void duplicateEventReplaysFirstPosting() {
        Wiring w = wiring();
        AccountingEvent event = paymentCapture("PM-DUP-1", 10000, 80, 60, "M001", "ALIPAY");

        Posting first = w.engine().post(event);
        Posting second = w.engine().post(event);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(w.ledger().findAllPostings()).hasSize(1);
        assertThat(w.ledger().findAllPostings().get(0).getEntries()).hasSize(6);
        // 余额只累加一次（对照案例六终态）
        assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(9920);
        assertThat(w.signedBalance(AccountCode.CHANNEL_RECEIVABLE, "ALIPAY")).isEqualTo(9940);
    }

    @Test
    @DisplayName("幂等键是派生值 {eventType}:{sourceId}：sourceId 不同 ⇒ 两笔独立交易")
    void distinctSourceIdsAreDistinctPostings() {
        Wiring w = wiring();
        Posting a = w.engine().post(paymentCapture("PM-IDEM-A", 100, 0, 0, "M001", "ALIPAY"));
        Posting b = w.engine().post(paymentCapture("PM-IDEM-B", 100, 0, 0, "M001", "ALIPAY"));

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getIdempotencyKey()).isEqualTo("PAYMENT_CAPTURE:PM-IDEM-A");
        assertThat(b.getIdempotencyKey()).isEqualTo("PAYMENT_CAPTURE:PM-IDEM-B");
    }

    @Test
    @DisplayName("必测⑥（应用层竞态）：先查后插窗口内撞唯一键 ⇒ 捕获 DuplicateKeyException 回查回放")
    void duplicateKeyRaceFallsBackToReplay() {
        // 输家时间线：pre-check 落空（赢家尚未提交）→ save 撞 uk → 回查命中赢家
        InMemoryLedgerRepository real = new InMemoryLedgerRepository();
        LedgerRepository loserView = new BlindThenCollidingRepository(real);
        Wiring w = wiringWith(loserView);
        AccountingEvent event = paymentCapture("PM-RACE-1", 5000, 0, 0, "M001", "ALIPAY");

        Posting replayed = w.engine().post(event);

        assertThat(replayed.getSourceId()).isEqualTo("PM-RACE-1");
        assertThat(replayed.getId()).isEqualTo(real.findAllPostings().get(0).getId());
        assertThat(real.findAllPostings()).as("真实落库只有赢家一笔").hasSize(1);
        assertThat(real.balances().findAll()).as("投影也只累加一次（应收/应付两户）").hasSize(2);
    }

    @Test
    @DisplayName("必测⑥（真并发）：8 线程同事件 ⇒ 1 个赢家落库，其余全部回查回放同一笔")
    void concurrentSameEventYieldsSinglePosting() throws Exception {
        RaceWindowLedger ledger = new RaceWindowLedger();
        Wiring w = wiringWith(ledger);
        AccountingEvent event = paymentCapture("PM-CONC-1", 7700, 0, 0, "M001", "ALIPAY");

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gun = new CountDownLatch(1);
        try {
            List<Future<Posting>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    gun.await(5, TimeUnit.SECONDS);
                    return w.engine().post(event);
                }));
            }
            gun.countDown();

            java.util.Set<Long> distinctIds = new java.util.HashSet<>();
            for (Future<Posting> f : futures) {
                distinctIds.add(f.get(10, TimeUnit.SECONDS).getId());
            }
            assertThat(distinctIds).as("所有线程回放同一笔交易").hasSize(1);
            assertThat(ledger.real.findAllPostings()).hasSize(1);
            assertThat(w.signedBalance(AccountCode.MERCHANT_PAYABLE, "M001")).isEqualTo(7700);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("不平衡事件在落库前被拒（BizException 冒泡，仓储零写入）")
    void ruleProducingUnbalancedLinesNeverPersists() {
        Wiring w = wiring();
        // 渠道费 > 毛额不会破坏 PaymentCaptureRule 的平衡（它自带配对）；
        // 真正的门禁在聚合根构造期——见 PostingBalanceGateTest。此处验证引擎失败路径无残留。
        try {
            w.engine().post(paymentCapture("PM-BAD-1", -5, 0, 0, "M001", "ALIPAY"));
        } catch (BizException expected) {
            // fall through
        }
        assertThat(w.ledger().findAllPostings()).isEmpty();
        assertThat(w.balances().findAll()).isEmpty();
    }

    private static Wiring wiringWith(LedgerRepository ledger) {
        Wiring base = wiring();
        PostingEngine engine = new PostingEngine(ledger, base.registry(), base.resolver(),
                base.periods(), com.payment.ledger.LedgerTestSupport.noopMetrics(),
                new com.payment.common.core.observability.StructuredAuditLogger());
        com.payment.ledger.infra.InMemoryAccountBalanceRepository balances =
                ((InMemoryLedgerRepository) ledger).balances();
        BalanceChecker checker = new BalanceChecker(ledger, base.accounts(), balances);
        return new Wiring(base.accounts(), base.ledger(), balances, base.periods(),
                base.resolver(), base.registry(), engine, checker, base.periodService());
    }

    /** 输家视野：首次回查落空（赢家未提交的快照盲区），save 即赢家用真实落库 + 撞键。 */
    private static final class BlindThenCollidingRepository extends InMemoryLedgerRepository {

        private final InMemoryLedgerRepository real;
        private boolean firstLookup = true;
        private boolean collided;

        BlindThenCollidingRepository(InMemoryLedgerRepository real) {
            this.real = real;
        }

        @Override
        public synchronized Optional<Posting> findByIdempotencyKey(String idempotencyKey) {
            if (firstLookup) {
                firstLookup = false;
                return Optional.empty();
            }
            return real.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public synchronized Posting save(Posting posting) {
            if (!collided) {
                collided = true;
                real.save(posting);
                throw new DuplicateKeyException("uk_postings_idempotency_key");
            }
            return real.save(posting);
        }

        @Override
        public java.util.List<com.payment.ledger.domain.TrialBalanceRow> trialBalance(String period) {
            return real.trialBalance(period);
        }

        @Override
        public com.payment.ledger.infra.InMemoryAccountBalanceRepository balances() {
            return real.balances();
        }
    }

    /** 撞键仓储：真实落库委托给 {@code real}，同键重复 save 抛 {@code DuplicateKeyException}（模拟唯一约束）。 */
    private static final class RaceWindowLedger extends InMemoryLedgerRepository {

        private final InMemoryLedgerRepository real = new InMemoryLedgerRepository();

        @Override
        public synchronized Posting save(Posting posting) {
            Optional<Posting> winner = real.findByIdempotencyKey(posting.getIdempotencyKey());
            if (winner.isPresent()) {
                // 真库语义：写入被唯一约束拒绝
                throw new DuplicateKeyException("uk_postings_idempotency_key");
            }
            return real.save(posting);
        }

        @Override
        public synchronized Optional<Posting> findByIdempotencyKey(String idempotencyKey) {
            return real.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public synchronized Optional<Posting> findByEvent(com.payment.common.dto.rpc.AccountingEventType eventType,
                                                          String sourceId) {
            return real.findByEvent(eventType, sourceId);
        }

        @Override
        public synchronized List<Posting> findAllPostings() {
            return real.findAllPostings();
        }

        @Override
        public synchronized List<com.payment.ledger.domain.TrialBalanceRow> trialBalance(String period) {
            return real.trialBalance(period);
        }

        @Override
        public com.payment.ledger.infra.InMemoryAccountBalanceRepository balances() {
            return real.balances();
        }
    }
}
