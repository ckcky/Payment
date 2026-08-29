package com.payment.payment.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.payment.application.LedgerPostingGateway;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记账出站网关实现（Feature 004 / ADR-0009 / FR-006、FR-010）：
 * 支付成功 → 经 Feign 同步 RPC 在账本留下「借贷平衡」的复式分录。
 *
 * <p>记账失败**不回滚**支付成功事实（禁 2PC/XA）：失败仅记录 {@code ledger.posting_failed}
 * 指标与告警日志，进入「待记账」清单，由 reconciliation 对账补齐（Saga + 幂等）。</p>
 *
 * <p>幂等键固定为 {@code PAYMENT:<支付幂等键>}，重复调用由账本唯一约束吸收，不产生重复分录。</p>
 */
public class FeignLedgerPostingGateway implements LedgerPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignLedgerPostingGateway.class);
    private static final String MODULE = "payment";

    /** 账本预置科目 ID（与 ledger-service Account 枚举一致，见 deployment/schema/09-ledger-schema.sql）。 */
    private static final long CUSTOMER_CASH = 1L;
    private static final long MERCHANT_PAYABLE = 2L;
    private static final long PLATFORM_FEE_REVENUE = 3L;

    private final LedgerFeignClient ledgerClient;
    private final BusinessMetrics metrics;

    public FeignLedgerPostingGateway(LedgerFeignClient ledgerClient, BusinessMetrics metrics) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
    }

    @Override
    public void postPaymentCapture(String idempotencyKey, Long paymentId, long amountMinor,
                                   long feeMinor, String currencyCode) {
        String postingKey = "PAYMENT:" + idempotencyKey;
        long netMinor = amountMinor - feeMinor;
        PostingRequest request = new PostingRequest(postingKey, "PAYMENT", String.valueOf(paymentId),
                currencyCode, buildEntries(amountMinor, feeMinor, netMinor));
        try {
            PostingResponse response = ledgerClient.post(request);
            metrics.counter("ledger.posting_succeeded", 1.0, "module", MODULE);
            log.info("记账成功 paymentId={} postingId={} entries={}", paymentId,
                    response.postingId(), response.entries().size());
        } catch (RuntimeException ex) {
            // 记账失败不回滚支付成功事实；记录待记账，交由重试/对账兜底（ADR-0009）
            metrics.counter("ledger.posting_failed", 1.0, "module", MODULE);
            log.error("记账失败，进入待记账兜底：paymentId={} postingKey={} reason={}",
                    paymentId, postingKey, ex.getMessage());
        }
    }

    /** 支付成功分录：借客户资金 A / 贷应付商户 N / 贷手续费收入 F（A = N + F，平衡）。 */
    private List<PostingRequest.EntryRequest> buildEntries(long amountMinor, long feeMinor, long netMinor) {
        List<PostingRequest.EntryRequest> entries = new ArrayList<>();
        entries.add(new PostingRequest.EntryRequest(CUSTOMER_CASH, "DEBIT", amountMinor, "PAYMENT_CAPTURE"));
        if (netMinor > 0) {
            entries.add(new PostingRequest.EntryRequest(MERCHANT_PAYABLE, "CREDIT", netMinor, "PAYMENT_CAPTURE"));
        }
        if (feeMinor > 0) {
            entries.add(new PostingRequest.EntryRequest(PLATFORM_FEE_REVENUE, "CREDIT", feeMinor, "FEE"));
        }
        return entries;
    }
}
