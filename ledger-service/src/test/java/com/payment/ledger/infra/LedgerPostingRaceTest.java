package com.payment.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.application.PostingEngine;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerSourceType;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 必测⑥（真库并发半部，H2/MySQL 模式 + 真唯一约束）：8 线程同一 PAYMENT_CAPTURE 事件并发
 * 投递 ⇒ 落库恰一笔交易、分录一份、投影一次——先查后插窗口由
 * {@code uk_postings_idempotency_key} 吸收，输家经 {@code DuplicateKeyException} 回查回放。
 *
 * <p>与 Testcontainers 真 MySQL 类（{@code LedgerPostingConcurrencyTest}）互补：那类验
 * 约束本体，本类验整条应用管道（引擎→规则→解析→保存→回放）在并发下的收敛。</p>
 */
@SpringBootTest
class LedgerPostingRaceTest {

    private static final int THREADS = 8;

    @Autowired
    private PostingEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM postings");
        jdbc.update("DELETE FROM account_balances");
        jdbc.update("DELETE FROM ledger_periods");
        jdbc.update("DELETE FROM accounts WHERE id > 14");
    }

    @Test
    @DisplayName("8 线程同事件 ⇒ 恰 1 笔交易 / 2 条分录 / 应付户贷余一次入账")
    void concurrentSameEventCollapsesToOnePosting() throws Exception {
        AccountingEvent event = new AccountingEvent(AccountingEventType.PAYMENT_CAPTURE,
                LedgerSourceType.PAYMENT, "PM-RACE-IT", "CNY", 7700L, 0L, 0L,
                "M001", "ALIPAY", null, null, null, null, null, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gun = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    gun.await(5, TimeUnit.SECONDS);
                    return engine.post(event).getId();
                }));
            }
            gun.countDown();

            var ids = new HashSet<Long>();
            for (Future<Long> f : futures) {
                ids.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).as("全部线程收敛到同一笔交易").hasSize(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM postings WHERE source_id = 'PM-RACE-IT'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entries", Integer.class)).isEqualTo(2);
            Long mpId = jdbc.queryForObject("SELECT id FROM accounts WHERE definition_code = ?"
                    + " AND owner_id = 'M001'", Long.class, AccountCode.MERCHANT_PAYABLE.name());
            assertThat(jdbc.queryForObject("SELECT credit_total FROM account_balances "
                    + "WHERE account_instance_id = ?", Long.class, mpId)).isEqualTo(7700L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发不同事件（同商户）⇒ 全部落库且互不覆盖（幂等键只吸收同键）")
    void concurrentDistinctEventsAllPersist() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gun = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int seq = i;
                futures.add(pool.submit(() -> {
                    gun.await(5, TimeUnit.SECONDS);
                    return engine.post(new AccountingEvent(AccountingEventType.PAYMENT_CAPTURE,
                            LedgerSourceType.PAYMENT, "PM-DIST-" + seq, "CNY", 100L, 0L, 0L,
                            "M001", "MOCK", null, null, null, null, null, null, null)).getId();
                }));
            }
            gun.countDown();
            var ids = new HashSet<Long>();
            for (Future<Long> f : futures) {
                ids.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(THREADS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postings", Integer.class))
                    .isEqualTo(THREADS);
        } finally {
            pool.shutdownNow();
        }
    }
}
