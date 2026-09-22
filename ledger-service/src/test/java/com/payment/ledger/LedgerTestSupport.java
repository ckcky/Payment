package com.payment.ledger;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.application.AccountResolver;
import com.payment.ledger.application.BalanceChecker;
import com.payment.ledger.application.PeriodService;
import com.payment.ledger.application.PostingEngine;
import com.payment.ledger.domain.AccountDefinition;
import com.payment.ledger.domain.AccountDefinition.AccountType;
import com.payment.ledger.domain.AccountDefinition.NormalSide;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.domain.posting.PostingRule;
import com.payment.ledger.domain.posting.PostingRuleRegistry;
import com.payment.ledger.domain.posting.rules.AdjustmentRule;
import com.payment.ledger.domain.posting.rules.ChannelFeeRule;
import com.payment.ledger.domain.posting.rules.ChannelSettlementRule;
import com.payment.ledger.domain.posting.rules.MerchantSettlementRule;
import com.payment.ledger.domain.posting.rules.PaymentCaptureRule;
import com.payment.ledger.domain.posting.rules.RefundRule;
import com.payment.ledger.infra.InMemoryAccountBalanceRepository;
import com.payment.ledger.infra.InMemoryAccountRepository;
import com.payment.ledger.infra.InMemoryLedgerPeriodRepository;
import com.payment.ledger.infra.InMemoryLedgerRepository;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

/**
 * 031 单测装配：InMemory 仓储 + 手工接线的 {@link PostingEngine}（不走 Spring），
 * seed 与 {@code deployment/schema/09-ledger-schema.sql} 的科目表/实例表逐行对齐。
 */
public final class LedgerTestSupport {

    public static final String CNY = "CNY";

    private LedgerTestSupport() {
    }

    /** 一整套接线完成的内存管道（各测试共享同一实例，观察其行为）。 */
    public record Wiring(InMemoryAccountRepository accounts,
                         InMemoryLedgerRepository ledger,
                         InMemoryAccountBalanceRepository balances,
                         InMemoryLedgerPeriodRepository periods,
                         AccountResolver resolver,
                         PostingRuleRegistry registry,
                         PostingEngine engine,
                         BalanceChecker balanceChecker,
                         PeriodService periodService) {

        /** 按 (科目, owner) 找实例 id；不存在返回空。 */
        public AccountInstance instance(AccountCode code, String ownerId) {
            return accounts.findInstance(code.name(), code.ownerDimension(), ownerId, CNY)
                    .orElseThrow(() -> new AssertionError(
                            "instance not seeded: " + code + ":" + ownerId));
        }

        /** 按正常余额方向取号的投影余额（借余 = 借-贷，贷余 = 贷-借）。 */
        public long signedBalance(AccountCode code, String ownerId) {
            return balanceChecker.balanceOf(instance(code, ownerId).getId(), CNY).balance();
        }

        /** 未取号的分录聚合（同一实例的借/贷发生额视图）。 */
        public BalanceChecker.BalanceView balanceView(AccountCode code, String ownerId) {
            return balanceChecker.balanceOf(instance(code, ownerId).getId(), CNY);
        }
    }

    public static Wiring wiring() {
        return wiring(List.of(new PaymentCaptureRule(), new RefundRule(), new ChannelFeeRule(),
                new ChannelSettlementRule(), new MerchantSettlementRule(), new AdjustmentRule()));
    }

    public static Wiring wiring(List<PostingRule> rules) {
        return wiring(rules, noopMetrics());
    }

    /** 可注入 metrics 的装配（spec 035 / T31：观测断言用例传入 MicrometerBusinessMetrics）。 */
    public static Wiring wiring(List<PostingRule> rules, BusinessMetrics metrics) {
        InMemoryAccountRepository accounts = seededAccounts();
        InMemoryLedgerRepository ledger = new InMemoryLedgerRepository();
        InMemoryLedgerPeriodRepository periods = new InMemoryLedgerPeriodRepository();
        AccountResolver resolver = new AccountResolver(accounts);
        PostingRuleRegistry registry = new PostingRuleRegistry(rules);
        invokeSelfCheck(registry);
        PostingEngine engine = new PostingEngine(ledger, registry, resolver, periods,
                metrics, new StructuredAuditLogger());
        BalanceChecker balanceChecker = new BalanceChecker(ledger, accounts, ledger.balances());
        PeriodService periodService = new PeriodService(ledger, balanceChecker, periods, metrics);
        return new Wiring(accounts, ledger, ledger.balances(), periods, resolver, registry,
                engine, balanceChecker, periodService);
    }

    /** seed 与 09 schema 对齐：8 定义 + LEGACY/哨兵/渠道/平台单例实例。 */
    public static InMemoryAccountRepository seededAccounts() {
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        def(accounts, AccountCode.CUSTOMER_CASH, "客户资金（历史科目）", AccountType.ASSET, NormalSide.DEBIT);
        def(accounts, AccountCode.MERCHANT_PAYABLE, "应付商户", AccountType.LIABILITY, NormalSide.CREDIT);
        def(accounts, AccountCode.FEE_REVENUE, "平台手续费收入", AccountType.REVENUE, NormalSide.CREDIT);
        def(accounts, AccountCode.SETTLEMENT_PAYABLE, "已结算待出款", AccountType.LIABILITY, NormalSide.CREDIT);
        def(accounts, AccountCode.SUSPENSE, "待处理差错款", AccountType.ASSET, NormalSide.DEBIT);
        def(accounts, AccountCode.CHANNEL_RECEIVABLE, "渠道应收", AccountType.ASSET, NormalSide.DEBIT);
        def(accounts, AccountCode.BANK_CASH, "平台银行现金", AccountType.ASSET, NormalSide.DEBIT);
        def(accounts, AccountCode.CHANNEL_FEE_EXPENSE, "渠道手续费成本", AccountType.EXPENSE, NormalSide.DEBIT);

        accounts.seedInstance("CUSTOMER_CASH", AccountCode.OwnerDimension.PLATFORM, "PLATFORM", CNY);
        accounts.seedInstance("MERCHANT_PAYABLE", AccountCode.OwnerDimension.MERCHANT, "LEGACY", CNY);
        accounts.seedInstance("SETTLEMENT_PAYABLE", AccountCode.OwnerDimension.MERCHANT, "LEGACY", CNY);
        accounts.seedInstance("FEE_REVENUE", AccountCode.OwnerDimension.PLATFORM, "PLATFORM", CNY);
        accounts.seedInstance("SUSPENSE", AccountCode.OwnerDimension.PLATFORM, "PLATFORM", CNY);
        accounts.seedInstance("BANK_CASH", AccountCode.OwnerDimension.PLATFORM, "PLATFORM", CNY);
        for (String channel : List.of("ALIPAY", "WECHAT", "DOUYIN", "MOCK")) {
            accounts.seedInstance("CHANNEL_RECEIVABLE", AccountCode.OwnerDimension.CHANNEL, channel, CNY);
            accounts.seedInstance("CHANNEL_FEE_EXPENSE", AccountCode.OwnerDimension.CHANNEL, channel, CNY);
        }
        return accounts;
    }

    private static void def(InMemoryAccountRepository accounts, AccountCode code, String name,
                            AccountType type, NormalSide normal) {
        accounts.seedDefinition(new AccountDefinition(code.name(), name, type, normal,
                code.ownerDimension(), code.status()));
    }

    private static void invokeSelfCheck(PostingRuleRegistry registry) {
        try {
            Method selfCheck = PostingRuleRegistry.class.getDeclaredMethod("selfCheck");
            selfCheck.setAccessible(true);
            selfCheck.invoke(registry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PostingRuleRegistry.selfCheck 不可调用（签名变更？）", e);
        }
    }

    public static BusinessMetrics noopMetrics() {
        return new BusinessMetrics() {
            @Override
            public void counter(String name, double value, String... tags) {
            }

            @Override
            public void timer(String name, Duration duration, String... tags) {
            }
        };
    }

    // ---------- 事件工厂（16 槽位顺序集中在此，测试不手铺参数） ----------

    public static AccountingEvent paymentCapture(String sourceId, long gross, long merchantFee,
                                                 long channelFee, String merchantId, String channelCode) {
        return new AccountingEvent(AccountingEventType.PAYMENT_CAPTURE, LedgerSourceType.PAYMENT,
                sourceId, CNY, gross, merchantFee, channelFee, merchantId, channelCode,
                null, null, null, null, null, null, null);
    }

    public static AccountingEvent refund(String sourceId, long gross, long merchantFeeRefund,
                                         long channelFeeRefund, String merchantId, String channelCode) {
        return new AccountingEvent(AccountingEventType.REFUND, LedgerSourceType.REFUND,
                sourceId, CNY, gross, merchantFeeRefund, channelFeeRefund, merchantId, channelCode,
                null, null, null, null, null, null, null);
    }

    public static AccountingEvent channelFee(String sourceId, long fee, String channelCode) {
        return new AccountingEvent(AccountingEventType.CHANNEL_FEE, LedgerSourceType.PAYMENT,
                sourceId, CNY, null, null, null, null, channelCode,
                null, null, null, null, fee, null, null);
    }

    public static AccountingEvent channelSettlement(String sourceId, long net, String channelCode) {
        return new AccountingEvent(AccountingEventType.CHANNEL_SETTLEMENT, LedgerSourceType.SETTLEMENT,
                sourceId, CNY, null, null, null, null, channelCode,
                net, null, null, null, null, null, null);
    }

    public static AccountingEvent merchantSettlement(String sourceId, long signedNet, String merchantId) {
        return new AccountingEvent(AccountingEventType.MERCHANT_SETTLEMENT, LedgerSourceType.SETTLEMENT,
                sourceId, CNY, null, null, null, merchantId, null,
                signedNet, null, null, null, null, null, null);
    }

    public static AccountingEvent adjustment(String sourceId, String kind, long amount,
                                             String fromAccountCode, String toAccountCode,
                                             AccountingEventType reversesEventType,
                                             String reversesSourceId, String merchantId) {
        return new AccountingEvent(AccountingEventType.ADJUSTMENT, LedgerSourceType.RECONCILIATION,
                sourceId, CNY, null, null, null, merchantId, null,
                null, kind, fromAccountCode, toAccountCode, amount, reversesEventType, reversesSourceId);
    }

    /** 分录摘要：{@code 方向 科目码:owner 金额} 列表，用于逐行断言。 */
    public static List<String> entrySummary(Wiring w, Posting posting) {
        return posting.getEntries().stream()
                .map(e -> e.getDirection() + " " + describe(w, e.getAccountId()) + " " + e.getAmountMinor())
                .toList();
    }

    private static String describe(Wiring w, long accountId) {
        return w.accounts().findInstanceById(accountId)
                .map(i -> i.getDefinitionCode() + ":" + i.getOwnerId())
                .orElse("unknown#" + accountId);
    }
}
