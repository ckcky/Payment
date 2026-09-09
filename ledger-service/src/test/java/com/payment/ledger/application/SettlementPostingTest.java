package com.payment.ledger.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.ledger.LedgerTestSupport;
import com.payment.ledger.domain.Account;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.infra.InMemoryLedgerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结算记账测试（spec 004 / US3 / T015 / SC-003）：结算批次在账本生成「应付→已结」平衡 Posting。
 *
 * <p>与 {@link LedgerPostingServiceTest} 中串联场景的分工：本类聚焦结算自身的结转语义——
 * 商户应付<b>减少 S</b>、结算应付<b>增加 S</b>（负债换科目而非消失），
 * 且重复结转同一批次不得二次入账。</p>
 */
class SettlementPostingTest {

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerPostingService service = new LedgerPostingService(repository,
            new NoopBusinessMetrics(), new StructuredAuditLogger());

    @Test
    void settlementMovesPayableToSettlementPayable() {
        // 支付 10000（手续费 300）→ 结算净额 9700
        service.post("PAYMENT:p1", LedgerSourceType.PAYMENT, "p1", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p1", 10_000, 300));
        Posting settlement = service.post("SETTLEMENT:s1", LedgerSourceType.SETTLEMENT, "s1", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s1", 9_700));

        assertThat(settlement.isBalanced()).isTrue();
        assertThat(debitTotal(settlement)).isEqualTo(creditTotal(settlement));
        // 借「应付商户」：负债从商户侧结转出去
        assertThat(settlement.getEntries()).anySatisfy(e -> {
            assertThat(e.getAccountId()).isEqualTo(Account.MERCHANT_PAYABLE.getId());
            assertThat(e.getDirection()).isEqualTo(LedgerEntry.Direction.DEBIT);
            assertThat(e.getAmountMinor()).isEqualTo(9_700L);
        });
        // 贷「结算应付」：转入待打款科目
        assertThat(settlement.getEntries()).anySatisfy(e -> {
            assertThat(e.getAccountId()).isEqualTo(Account.SETTLEMENT_PAYABLE.getId());
            assertThat(e.getDirection()).isEqualTo(LedgerEntry.Direction.CREDIT);
            assertThat(e.getAmountMinor()).isEqualTo(9_700L);
        });
        // 结转后：商户应付归零、结算应付 9700（负债增加 ⇒ 贷方为负）
        assertThat(new BalanceChecker(repository).accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY"))
                .isZero();
        assertThat(new BalanceChecker(repository).accountBalance(Account.SETTLEMENT_PAYABLE.getId(), "CNY"))
                .isEqualTo(-9_700);
        assertThat(new BalanceChecker(repository).isBalanced()).isTrue();
    }

    @Test
    void settlementAfterPartialRefundSettlesOnlyRemainingPayable() {
        service.post("PAYMENT:p2", LedgerSourceType.PAYMENT, "p2", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p2", 8_000, 200));
        service.post("REFUND:r2", LedgerSourceType.REFUND, "r2", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r2", 3_000));
        service.post("SETTLEMENT:s2", LedgerSourceType.SETTLEMENT, "s2", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s2", 4_800));

        // 7800 应付 - 3000 退款 - 4800 结转 = 0
        assertThat(new BalanceChecker(repository).accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY"))
                .isZero();
        assertThat(new BalanceChecker(repository).isBalanced()).isTrue();
    }

    @Test
    void duplicateSettlementIsIdempotentAndDoesNotDoubleTransfer() {
        service.post("PAYMENT:p3", LedgerSourceType.PAYMENT, "p3", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p3", 6_000, 0));
        service.post("SETTLEMENT:s3", LedgerSourceType.SETTLEMENT, "s3", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s3", 6_000));
        long settlementPayableAfterFirst = new BalanceChecker(repository)
                .accountBalance(Account.SETTLEMENT_PAYABLE.getId(), "CNY");
        int entriesAfterFirst = repository.findAllEntries().size();

        service.post("SETTLEMENT:s3", LedgerSourceType.SETTLEMENT, "s3", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s3", 6_000));

        // 幂等吸收：重复结转同一批次不产生第二组分录（否则结算应付会被重复贷记）
        assertThat(repository.findAllEntries()).hasSize(entriesAfterFirst);
        assertThat(new BalanceChecker(repository).accountBalance(Account.SETTLEMENT_PAYABLE.getId(), "CNY"))
                .isEqualTo(settlementPayableAfterFirst);
        assertThat(repository.findByIdempotencyKey("SETTLEMENT:s3")).isPresent();
    }

    @Test
    void settlementEntriesAreTraceableToBatchSource() {
        service.post("SETTLEMENT:s4", LedgerSourceType.SETTLEMENT, "s4", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s4", 2_500));

        List<LedgerEntry> bySource = repository.findEntriesBySource(LedgerSourceType.SETTLEMENT, "s4");
        assertThat(bySource).hasSize(2);
        assertThat(bySource).allSatisfy(e -> {
            assertThat(e.getSourceType()).isEqualTo(LedgerSourceType.SETTLEMENT);
            assertThat(e.getSourceId()).isEqualTo("s4");
            assertThat(e.getEntryType()).isEqualTo(LedgerEntry.Type.SETTLEMENT);
        });
    }

    @Test
    void unbalancedSettlementIsRejectedAndLeavesNoTrace() {
        List<LedgerEntry> unbalanced = List.of(
                LedgerTestSupport.entry(LedgerSourceType.SETTLEMENT, "s5", Account.MERCHANT_PAYABLE,
                        LedgerEntry.Direction.DEBIT, 2_000, LedgerEntry.Type.SETTLEMENT),
                LedgerTestSupport.entry(LedgerSourceType.SETTLEMENT, "s5", Account.SETTLEMENT_PAYABLE,
                        LedgerEntry.Direction.CREDIT, 1_900, LedgerEntry.Type.SETTLEMENT));

        assertThatThrownBy(() -> service.post("SETTLEMENT:s5", LedgerSourceType.SETTLEMENT, "s5", "CNY",
                unbalanced))
                .isInstanceOf(BizException.class);

        assertThat(repository.findAllEntries()).isEmpty();
        assertThat(new BalanceChecker(repository).isBalanced()).isTrue();
    }

    private long debitTotal(Posting posting) {
        return posting.getEntries().stream()
                .filter(e -> e.getDirection() == LedgerEntry.Direction.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
    }

    private long creditTotal(Posting posting) {
        return posting.getEntries().stream()
                .filter(e -> e.getDirection() == LedgerEntry.Direction.CREDIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
    }
}
