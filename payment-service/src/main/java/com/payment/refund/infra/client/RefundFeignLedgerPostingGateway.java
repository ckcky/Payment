package com.payment.refund.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.refund.application.LedgerPostingGateway;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 退款记账出站网关实现（Feature 005 / ADR-0018）：退款成功 → 经 Feign 同步 RPC 在账本留下冲正分录。
 *
 * <p>冲正分录与支付成功反向：借应付商户（MERCHANT_PAYABLE 2）/ 贷客户资金（CUSTOMER_CASH 1），
 * 金额 = 实际退款额，借贷平衡。记账失败**不回滚**退款成功事实（禁 2PC/XA），仅记录
 * {@code ledger.posting_failed} 指标与告警日志，由重试/对账兜底（同 ADR-0009 取舍）。</p>
 *
 * <p>幂等键固定为 {@code REFUND:<退款幂等键>}，重复调用由账本唯一约束吸收，不产生重复分录。</p>
 */
@Component
public class RefundFeignLedgerPostingGateway implements LedgerPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(RefundFeignLedgerPostingGateway.class);
    private static final String MODULE = "refund";

    /** 账本预置科目 ID（与 ledger-service Account 枚举一致，见 deployment/schema/09-ledger-schema.sql）。 */
    private static final long CUSTOMER_CASH = 1L;
    private static final long MERCHANT_PAYABLE = 2L;

    private final LedgerFeignClient ledgerClient;
    private final BusinessMetrics metrics;

    public RefundFeignLedgerPostingGateway(LedgerFeignClient ledgerClient, BusinessMetrics metrics) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
    }

    @Override
    public void postRefundCapture(String idempotencyKey, Long refundId, long amountMinor, String currencyCode) {
        if (amountMinor <= 0) {
            log.warn("跳过退款记账：金额为 0 或非正（账本要求分录金额 > 0），refundId={}", refundId);
            return;
        }
        String postingKey = "REFUND:" + idempotencyKey;
        PostingRequest request = new PostingRequest(postingKey, "REFUND", String.valueOf(refundId),
                currencyCode, buildEntries(amountMinor));
        try {
            PostingResponse response = ledgerClient.post(request);
            metrics.counter("ledger.posting_succeeded", 1.0, "module", MODULE);
            log.info("退款记账成功 refundId={} postingId={} entries={}", refundId,
                    response.postingId(), response.entries().size());
        } catch (RuntimeException ex) {
            // 记账失败不回滚退款成功事实；记录待记账，交由重试/对账兜底（ADR-0018）
            metrics.counter("ledger.posting_failed", 1.0, "module", MODULE);
            log.error("退款记账失败，进入待记账兜底：refundId={} postingKey={} reason={}",
                    refundId, postingKey, ex.getMessage());
        }
    }

    /** 退款冲正分录：借应付商户 2 / 贷客户资金 1（与支付成功反向，平衡）。 */
    private List<PostingRequest.EntryRequest> buildEntries(long amountMinor) {
        List<PostingRequest.EntryRequest> entries = new ArrayList<>();
        entries.add(new PostingRequest.EntryRequest(MERCHANT_PAYABLE, "DEBIT", amountMinor, "REFUND"));
        entries.add(new PostingRequest.EntryRequest(CUSTOMER_CASH, "CREDIT", amountMinor, "REFUND"));
        return entries;
    }
}
