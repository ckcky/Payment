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
 * 退款记账测试（spec 004 / US2 / T013 / SC-002）：确认退款生成平衡冲正分录，重复记账被幂等吸收。
 *
 * <p>与 {@link LedgerPostingServiceTest#refundAndSettlementPostBalancedReversals} 的分工：
 * 后者覆盖「支付→退款→结算」串起来的全局平衡，本类聚焦退款自身的冲正语义——
 * 方向必须<b>与支付相反</b>、商户应付必须<b>按退款额减少</b>、来源必须<b>可溯源到 REFUND</b>，
 * 且重复退款不得二次入账。</p>
 */
class RefundPostingTest {

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerPostingService service = new LedgerPostingService(repository,
            new NoopBusinessMetrics(), new StructuredAuditLogger());

    @Test
    void fullRefundReversesMerchantPayableExactly() {
        // 支付 10000（手续费 0，商户应付净额 10000）→ 全额退款 10000
        service.post("PAYMENT:p1", LedgerSourceType.PAYMENT, "p1", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p1", 10_000, 0));
        Posting refund = service.post("REFUND:r1", LedgerSourceType.REFUND, "r1", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r1", 10_000));

        assertThat(refund.isBalanced()).isTrue();
        assertThat(debitTotal(refund)).isEqualTo(creditTotal(refund));
        // 退款与支付方向相反：借「应付商户」（冲减负债）/ 贷「客户资金」（钱退回）
        assertThat(refund.getEntries()).anySatisfy(e -> {
            assertThat(e.getAccountId()).isEqualTo(Account.MERCHANT_PAYABLE.getId());
            assertThat(e.getDirection()).isEqualTo(LedgerEntry.Direction.DEBIT);
            assertThat(e.getAmountMinor()).isEqualTo(10_000L);
        });
        assertThat(refund.getEntries()).anySatisfy(e -> {
            assertThat(e.getAccountId()).isEqualTo(Account.CUSTOMER_CASH.getId());
            assertThat(e.getDirection()).isEqualTo(LedgerEntry.Direction.CREDIT);
            assertThat(e.getAmountMinor()).isEqualTo(10_000L);
        });
        // 全额退款后商户应付归零，全局仍平衡
        assertThat(new BalanceChecker(repository).accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY"))
                .isZero();
        assertThat(new BalanceChecker(repository).isBalanced()).isTrue();
    }

    @Test
    void partialRefundLeavesRemainingPayableIntact() {
        service.post("PAYMENT:p2", LedgerSourceType.PAYMENT, "p2", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p2", 8_000, 200));
        service.post("REFUND:r2", LedgerSourceType.REFUND, "r2", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r2", 3_000));

        // 商户应付：7800(贷) - 3000(借) = 4800 待结；「借方为正」口径下负债余额为负 ⇒ -4800
        assertThat(new BalanceChecker(repository).accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY"))
                .isEqualTo(-4_800);
        assertThat(new BalanceChecker(repository).isBalanced()).isTrue();
    }

    @Test
    void duplicateRefundIsIdempotentAndDoesNotDoubleReverse() {
        service.post("PAYMENT:p3", LedgerSourceType.PAYMENT, "p3", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p3", 5_000, 0));
        service.post("REFUND:r3", LedgerSourceType.REFUND, "r3", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r3", 2_000));
        long payableAfterFirst = new BalanceChecker(repository)
                .accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY");
        int entriesAfterFirst = repository.findAllEntries().size();

        Posting again = service.post("REFUND:r3", LedgerSourceType.REFUND, "r3", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r3", 2_000));

        // 幂等吸收：不新增分录、不改变余额（否则重复退款会二次冲减负债）
        assertThat(repository.findAllEntries()).hasSize(entriesAfterFirst);
        assertThat(new BalanceChecker(repository).accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY"))
                .isEqualTo(payableAfterFirst);
        assertThat(again.getId()).isNotNull();
        assertThat(repository.findByIdempotencyKey("REFUND:r3")).isPresent();
    }

    @Test
    void refundEntriesAreTraceableToRefundSource() {
        service.post("REFUND:r4", LedgerSourceType.REFUND, "r4", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r4", 1_500));

        List<LedgerEntry> bySource = repository.findEntriesBySource(LedgerSourceType.REFUND, "r4");
        assertThat(bySource).hasSize(2);
        assertThat(bySource).allSatisfy(e -> {
            assertThat(e.getSourceType()).isEqualTo(LedgerSourceType.REFUND);
            assertThat(e.getSourceId()).isEqualTo("r4");
            assertThat(e.getEntryType()).isEqualTo(LedgerEntry.Type.REFUND);
        });
        assertThat(repository.findBySource(LedgerSourceType.REFUND, "r4")).hasSize(1);
    }

    @Test
    void unbalancedRefundIsRejectedAndLeavesNoTrace() {
        List<LedgerEntry> unbalanced = List.of(
                LedgerTestSupport.entry(LedgerSourceType.REFUND, "r5", Account.MERCHANT_PAYABLE,
                        LedgerEntry.Direction.DEBIT, 1_000, LedgerEntry.Type.REFUND),
                LedgerTestSupport.entry(LedgerSourceType.REFUND, "r5", Account.CUSTOMER_CASH,
                        LedgerEntry.Direction.CREDIT, 900, LedgerEntry.Type.REFUND));

        assertThatThrownBy(() -> service.post("REFUND:r5", LedgerSourceType.REFUND, "r5", "CNY",
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
