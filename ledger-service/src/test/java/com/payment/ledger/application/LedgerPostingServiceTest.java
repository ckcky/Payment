package com.payment.ledger.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.ledger.LedgerTestSupport;
import com.payment.ledger.domain.Account;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.infra.InMemoryLedgerRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记账服务测试（spec US1~US3 / FR-001~FR-004）：
 * 支付/退款/结算均生成平衡分录；重复幂等吸收；不平衡被拒绝且不落任何分录。
 */
class LedgerPostingServiceTest {

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerPostingService service = new LedgerPostingService(repository,
            new NoopBusinessMetrics(), new StructuredAuditLogger());

    @Test
    void paymentCapturePostsBalancedEntries() {
        Posting posting = service.post("PAYMENT:p1", LedgerSourceType.PAYMENT, "p1", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p1", 10_000, 300));

        assertThat(posting.getId()).isNotNull();
        assertThat(posting.isBalanced()).isTrue();
        assertThat(posting.getEntries()).hasSize(3);
        assertThat(debitTotal(posting)).isEqualTo(creditTotal(posting));
    }

    @Test
    void duplicatePostingIsIdempotent() {
        service.post("PAYMENT:p2", LedgerSourceType.PAYMENT, "p2", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p2", 5_000, 100));
        int entriesAfterFirst = repository.findAllEntries().size();

        Posting again = service.post("PAYMENT:p2", LedgerSourceType.PAYMENT, "p2", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p2", 5_000, 100));

        assertThat(again.getId()).isNotNull();
        assertThat(repository.findAllEntries()).hasSize(entriesAfterFirst);
        assertThat(repository.findByIdempotencyKey("PAYMENT:p2")).isPresent();
    }

    @Test
    void unbalancedPostingIsRejected() {
        List<LedgerEntry> unbalanced = List.of(
                LedgerTestSupport.entry(LedgerSourceType.PAYMENT, "p3", Account.CUSTOMER_CASH,
                        LedgerEntry.Direction.DEBIT, 1_000, LedgerEntry.Type.PAYMENT_CAPTURE),
                LedgerTestSupport.entry(LedgerSourceType.PAYMENT, "p3", Account.MERCHANT_PAYABLE,
                        LedgerEntry.Direction.CREDIT, 900, LedgerEntry.Type.PAYMENT_CAPTURE));

        assertThatThrownBy(() -> service.post("PAYMENT:p3", LedgerSourceType.PAYMENT, "p3", "CNY",
                unbalanced))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCodes.LEDGER_UNBALANCED);

        assertThat(repository.findAllEntries()).isEmpty();
    }

    @Test
    void refundAndSettlementPostBalancedReversals() {
        // 支付 8000（手续费 200，商户应付净额 7800）→ 部分退款 3000 → 结算剩余 4800
        service.post("PAYMENT:p4", LedgerSourceType.PAYMENT, "p4", "CNY",
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p4", 8_000, 200));
        service.post("REFUND:r4", LedgerSourceType.REFUND, "r4", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r4", 3_000));
        service.post("SETTLEMENT:s4", LedgerSourceType.SETTLEMENT, "s4", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s4", 4_800));

        BalanceChecker checker = new BalanceChecker(repository);
        assertThat(checker.isBalanced()).isTrue();
        // accountBalance 以「借方为正、贷方为负」记账：
        // 商户应付 7800(贷) - 3000(借,退款) - 4800(借,结算结转) = 0
        assertThat(checker.accountBalance(Account.MERCHANT_PAYABLE.getId(), "CNY")).isZero();
        // 结算应付被贷记 4800（负债增加，故为负）
        assertThat(checker.accountBalance(Account.SETTLEMENT_PAYABLE.getId(), "CNY")).isEqualTo(-4_800);
        // 客户资金：8000(借,收款) - 3000(贷,退款) = 5000 留存
        assertThat(checker.accountBalance(Account.CUSTOMER_CASH.getId(), "CNY")).isEqualTo(5_000);
        // 平台手续费收入被贷记 200（收入增加，故为负）
        assertThat(checker.accountBalance(Account.PLATFORM_FEE_REVENUE.getId(), "CNY")).isEqualTo(-200);
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
