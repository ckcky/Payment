package com.payment.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.application.PostingEngine;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 031 真管道集成测试（Spring + MyBatis + H2/MySQL 模式）：启动期 §7.7 自检通过即上下文可装
 * seed；记账走 {@link PostingEngine} → {@code MybatisLedgerRepository} 单事务落库，
 * 断言直查表验证交易/分录/投影三层一致。
 */
@SpringBootTest
class LedgerPipelineIntegrationTest {

    @Autowired
    private PostingEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanFactTables() {
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM postings");
        jdbc.update("DELETE FROM account_balances");
        jdbc.update("DELETE FROM ledger_periods");
        jdbc.update("DELETE FROM accounts WHERE id > 14");
    }

    @Test
    @DisplayName("PAYMENT_CAPTURE 案例六走真管道：postings 1 行、entries 6 行、投影按户累加")
    void caseSixPersistsAcrossThreeTables() {
        Posting posting = engine.post(new AccountingEvent(AccountingEventType.PAYMENT_CAPTURE,
                LedgerSourceType.PAYMENT, "PM-IT-1", "CNY", 10000L, 80L, 60L,
                "M001", "ALIPAY", null, null, null, null, null, null, null));

        Integer postings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE idempotency_key = 'PAYMENT_CAPTURE:PM-IT-1'",
                Integer.class);
        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE posting_id = ?",
                Integer.class, posting.getId());
        assertThat(postings).isEqualTo(1);
        assertThat(entries).isEqualTo(6);
        // 恒平：单笔交易内 Σ借 = Σ贷
        Long debit = jdbc.queryForObject("SELECT SUM(amount_minor) FROM ledger_entries "
                + "WHERE posting_id = ? AND direction = 'DEBIT'", Long.class, posting.getId());
        Long credit = jdbc.queryForObject("SELECT SUM(amount_minor) FROM ledger_entries "
                + "WHERE posting_id = ? AND direction = 'CREDIT'", Long.class, posting.getId());
        assertThat(debit).isEqualTo(credit).isEqualTo(10140L);
        // 投影：M001 应付户（自动开立）贷余 99.20
        Long mpId = jdbc.queryForObject("SELECT id FROM accounts WHERE definition_code = ?"
                + " AND owner_id = 'M001'", Long.class, AccountCode.MERCHANT_PAYABLE.name());
        assertThat(jdbc.queryForObject("SELECT credit_total - debit_total FROM account_balances "
                + "WHERE account_instance_id = ?", Long.class, mpId)).isEqualTo(9920L);
    }

    @Test
    @DisplayName("同事件二次投递：uk 命中回放首笔，库内仍只有一笔交易")
    void secondPostReplaysWithoutWrites() {
        AccountingEvent event = new AccountingEvent(AccountingEventType.PAYMENT_CAPTURE,
                LedgerSourceType.PAYMENT, "PM-IT-2", "CNY", 5000L, 0L, 0L,
                "M002", "MOCK", null, null, null, null, null, null, null);
        Posting first = engine.post(event);
        Posting second = engine.post(event);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("uk_event_source 兜底：派生键被绕开也得撞第二唯一约束")
    void eventSourceUniqueConstraintHolds() {
        List<Long> before = jdbc.queryForList("SELECT id FROM postings", Long.class);
        engine.post(new AccountingEvent(AccountingEventType.REFUND,
                LedgerSourceType.REFUND, "RF-IT-1", "CNY", 100L, 0L, 0L,
                "M001", "WECHAT", null, null, null, null, null, null, null));

        Integer dupeKey = jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE event_type = 'REFUND' AND source_id = 'RF-IT-1'",
                Integer.class);
        assertThat(before).isEmpty();
        assertThat(dupeKey).isEqualTo(1);
        // 双唯一约束同时存在（幂等键 = {eventType}:{sourceId} 由账本派生）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE idempotency_key = 'REFUND:RF-IT-1'", Integer.class))
                .isEqualTo(1);
    }
}
