package com.payment.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.application.PostingEngine;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.infra.persistence.LedgerEntryEntity;
import com.payment.ledger.infra.persistence.LedgerEntryMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 必测⑧（spec 031 §10 构造性保证）：分录写入中途失败 ⇒ 单事务整体回滚——
 * 交易头、分录、余额投影一律不留（「Posting 失败不能留下部分分录」），
 * 且失败不烧幂等键，同事件可重试成功。
 *
 * <p>用 {@link MockitoBean} 替换 {@code ledger_entries} mapper，在第一条分录处抛
 * 基础设施异常——此刻 posting 头已在事务内插入，回滚必须把它一并带走。</p>
 */
@SpringBootTest
class LedgerPostingAtomicityTest {

    @Autowired
    private PostingEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private LedgerEntryMapper entryMapper;

    private final AtomicInteger insertCalls = new AtomicInteger();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM postings");
        jdbc.update("DELETE FROM account_balances");
        jdbc.update("DELETE FROM accounts WHERE id > 14");
        insertCalls.set(0);
    }

    @Test
    @DisplayName("分录阶段抛错 ⇒ postings / account_balances 零残留；重试成功且只落一笔")
    void midSaveFailureRollsBackEverythingAndKeyIsNotBurned() {
        doAnswer(invocation -> {
            if (insertCalls.incrementAndGet() == 1) {
                throw new RuntimeException("simulated fault on first entry");
            }
            return 1;
        }).when(entryMapper).insert(any(LedgerEntryEntity.class));

        AccountingEvent event = new AccountingEvent(AccountingEventType.PAYMENT_CAPTURE,
                LedgerSourceType.PAYMENT, "PM-ATOMIC-1", "CNY", 10000L, 0L, 0L,
                "M001", "ALIPAY", null, null, null, null, null, null, null);

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> engine.post(event))
                .withMessageContaining("simulated fault");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postings", Integer.class))
                .as("交易头随事务回滚").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_balances", Integer.class))
                .as("投影随事务回滚").isZero();

        Posting retry = engine.post(event);
        assertThat(retry.getSourceId()).isEqualTo("PM-ATOMIC-1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE source_id = 'PM-ATOMIC-1'", Integer.class))
                .as("失败不烧幂等键，重试恰好一笔").isEqualTo(1);
    }
}
