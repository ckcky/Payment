package com.payment.ledger.application;

import static com.payment.ledger.LedgerTestSupport.seededAccounts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.infra.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AccountResolver（spec 031 原则 7 / §5.4）：三维度解析策略 + LEGACY 门禁。
 */
class AccountResolverTest {

    private InMemoryAccountRepository accounts;
    private AccountResolver resolver;

    @BeforeEach
    void setUp() {
        accounts = seededAccounts();
        resolver = new AccountResolver(accounts);
    }

    @Test
    @DisplayName("PLATFORM 单例：ownerKey 被忽略，恒落 seed 单例")
    void platformSingleton() {
        long withKey = resolver.resolve(PostingLine.debit("BANK_CASH", "whoever", 100), "CNY");
        long withoutKey = resolver.resolve(PostingLine.debit("BANK_CASH", null, 100), "CNY");
        assertThat(withoutKey).isEqualTo(withKey);
    }

    @Test
    @DisplayName("CHANNEL 维度：未注册渠道 fail fast（LEDGER_CHANNEL_UNKNOWN），不静默开新户")
    void unknownChannelRefused() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> resolver.resolve(
                        PostingLine.debit("CHANNEL_RECEIVABLE", "STRIPE", 100), "CNY"))
                .matches(ex -> ErrorCodes.LEDGER_CHANNEL_UNKNOWN.equals(ex.getCode()));
        assertThat(accounts.findInstance("CHANNEL_RECEIVABLE", AccountCode.OwnerDimension.CHANNEL,
                "STRIPE", "CNY")).isEmpty();
    }

    @Test
    @DisplayName("CHANNEL 维度：ownerKey 缺失同样拒绝（配错不猜默认）")
    void channelWithoutOwnerRefused() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> resolver.resolve(
                        PostingLine.debit("CHANNEL_RECEIVABLE", " ", 100), "CNY"))
                .matches(ex -> ErrorCodes.LEDGER_CHANNEL_UNKNOWN.equals(ex.getCode()));
    }

    @Test
    @DisplayName("MERCHANT 维度：缺户自动开立且幂等（两次解析同 id）")
    void merchantOpensInstanceOnDemandIdempotently() {
        long first = resolver.resolve(PostingLine.credit("MERCHANT_PAYABLE", "M-NEW", 100), "CNY");
        long second = resolver.resolve(PostingLine.credit("MERCHANT_PAYABLE", "M-NEW", 100), "CNY");

        assertThat(second).isEqualTo(first);
        assertThat(accounts.findInstance("MERCHANT_PAYABLE", AccountCode.OwnerDimension.MERCHANT,
                "M-NEW", "CNY")).isPresent();
    }

    @Test
    @DisplayName("MERCHANT 维度 ownerKey 缺省：落 LEGACY 哨兵实例（既有事实总口径），不新开哨兵")
    void merchantWithoutOwnerHitsSentinel() {
        long id = resolver.resolve(PostingLine.credit("MERCHANT_PAYABLE", null, 100), "CNY");

        assertThat(id).isEqualTo(accounts.findInstance("MERCHANT_PAYABLE",
                AccountCode.OwnerDimension.MERCHANT, "LEGACY", "CNY").orElseThrow().getId());
    }

    @Test
    @DisplayName("LEGACY 定义被新行引用 ⇒ LEDGER_ACCOUNT_LEGACY；红冲 fixedAccountId 行合法触达")
    void legacyGate() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> resolver.resolve(
                        PostingLine.debit("CUSTOMER_CASH", null, 100), "CNY"))
                .matches(ex -> ErrorCodes.LEDGER_ACCOUNT_LEGACY.equals(ex.getCode()));

        long legacyId = accounts.findInstance("CUSTOMER_CASH", AccountCode.OwnerDimension.PLATFORM,
                "PLATFORM", "CNY").orElseThrow().getId();
        assertThat(resolver.resolve(PostingLine.ofInstance(legacyId, LedgerEntry.Direction.CREDIT, 100),
                "CNY")).isEqualTo(legacyId);
    }

    @Test
    @DisplayName("fixedAccountId 指向不存在实例 ⇒ NOT_FOUND（红冲锚点悬空不猜）")
    void danglingReversalAnchorRefused() {
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> resolver.resolve(PostingLine.ofInstance(999_999L,
                        LedgerEntry.Direction.DEBIT, 100), "CNY"))
                .matches(ex -> ErrorCodes.NOT_FOUND.equals(ex.getCode()));
    }
}
