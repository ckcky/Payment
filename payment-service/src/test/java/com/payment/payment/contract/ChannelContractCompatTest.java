package com.payment.payment.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.core.rpc.TransportCode;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.common.dto.channel.PayCredential;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 渠道统一契约的<b>向后兼容</b>测试（spec 030 / T117、T118 / FR-105~FR-112，SC-A-02）。
 *
 * <p>本类守住 spec 030 的核心零破坏承诺：契约由窄变宽后，<b>既有调用点零改动即可编译运行</b>。</p>
 * <ul>
 *   <li>三条记录均<b>保留旧参数个数的兼容构造器</b>，且扩展字段回落 {@code null}；</li>
 *   <li>{@link ChannelResult} 旧 5 参构造可用、旧工厂零改动；</li>
 *   <li>{@link ChannelResult#withReason} <b>MUST 保留 credential</b>（FR-112）——
 *       否则重试耗尽标注会顺带抹掉「买家去哪儿付款」。</li>
 * </ul>
 */
class ChannelContractCompatTest {

    // ---------- 兼容构造器：旧参个数可用，扩展字段为 null ----------

    @Test
    @DisplayName("ChargeRequest 4 参兼容构造：扩展字段全部 null")
    void chargeRequestLegacyConstructorLeavesExtensionsNull() {
        ChargeRequest req = new ChargeRequest("PM1", 12_345L, "CNY", "MOCK");

        assertThat(req.paymentNo()).isEqualTo("PM1");
        // spec 037 / T3 重构：attemptId 分量已移除（ADR-0063 禁止数值主键进跨域契约），
        // 原 assertThat(req.attemptId()).isEqualTo(7L) 随字段一并删除。
        assertThat(req.amountMinor()).isEqualTo(12_345L);
        assertThat(req.currencyCode()).isEqualTo("CNY");
        assertThat(req.channelCode()).isEqualTo("MOCK");
        assertThat(req.scene()).isNull();
        assertThat(req.goods()).isNull();
        assertThat(req.expireAt()).isNull();
        assertThat(req.payer()).isNull();
        assertThat(req.attach()).isNull();
        assertThat(req.channelExtra()).isNull();
    }

    @Test
    @DisplayName("ChargeRequest 11 参构造：可携带全部扩展字段")
    void chargeRequestFullConstructorCarriesExtensions() {
        Instant expire = Instant.parse("2026-09-20T00:00:00Z");
        ChargeRequest req = new ChargeRequest("PM1", 100L, "CNY", "ALIPAY",
                null, null, expire, null, "attach-1",
                Map.of("subject", "测试商品"), null);

        assertThat(req.expireAt()).isEqualTo(expire);
        assertThat(req.attach()).isEqualTo("attach-1");
        assertThat(req.channelExtra()).containsEntry("subject", "测试商品");
    }

    @Test
    @DisplayName("RefundRequest 5 参兼容构造：扩展字段全部 null")
    void refundRequestLegacyConstructorLeavesExtensionsNull() {
        RefundRequest req = new RefundRequest("PM1", "PMRF1", 5_000L, "CNY", "MOCK");

        assertThat(req.paymentNo()).isEqualTo("PM1");
        assertThat(req.refundNo()).isEqualTo("PMRF1");
        assertThat(req.amountMinor()).isEqualTo(5_000L);
        assertThat(req.channelTransactionId()).isNull();
        assertThat(req.outRequestNo()).isNull();
        assertThat(req.reason()).isNull();
        assertThat(req.refundNotifyUrl()).isNull();
    }

    @Test
    @DisplayName("QueryStatusRequest 3 参兼容构造：channelTransactionId 为 null")
    void queryStatusRequestLegacyConstructorLeavesChannelTxnNull() {
        QueryStatusRequest req = new QueryStatusRequest("PM1", "TX1", "idem-1");

        assertThat(req.paymentNo()).isEqualTo("PM1");
        assertThat(req.transactionId()).isEqualTo("TX1");
        assertThat(req.idempotencyKey()).isEqualTo("idem-1");
        assertThat(req.channelTransactionId()).isNull();
    }

    @Test
    @DisplayName("QueryStatusRequest 4 参构造：携带渠道交易号")
    void queryStatusRequestFullConstructorCarriesChannelTxn() {
        QueryStatusRequest req = new QueryStatusRequest("PM1", "TX1", "idem-1", "ch-txn-9");

        assertThat(req.channelTransactionId()).isEqualTo("ch-txn-9");
    }

    // ---------- ChannelResult：旧形态零破坏 + credential 语义 ----------

    @Test
    @DisplayName("ChannelResult 5 参兼容构造：credential 为 null，hasCredential()=false")
    void channelResultLegacyConstructorHasNullCredential() {
        ChannelResult r = new ChannelResult(ChannelResult.Status.UNKNOWN, "ch-1", "why",
                TransportCode.TIMEOUT, BusinessCode.UNKNOWN);

        assertThat(r.credential()).isNull();
        assertThat(r.hasCredential()).isFalse();
    }

    @Test
    @DisplayName("既有工厂零改动：success/timeout/businessFailure/businessUnknown 均无凭证")
    void existingFactoriesRemainCredentialFree() {
        assertThat(ChannelResult.success("ch-1").hasCredential()).isFalse();
        assertThat(ChannelResult.timeout("t").hasCredential()).isFalse();
        assertThat(ChannelResult.businessFailure("ch-1", "declined").hasCredential()).isFalse();
        assertThat(ChannelResult.businessUnknown("no conclusion").hasCredential()).isFalse();
    }

    @Test
    @DisplayName("accepted(...) 两参重载仍可用且无凭证（spec 019 兼容）")
    void acceptedTwoArgOverloadStillWorks() {
        ChannelResult r = ChannelResult.accepted("ch-1", "awaiting async callback");

        assertThat(r.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(r.channelReference()).isEqualTo("ch-1");
        assertThat(r.hasCredential()).isFalse();
    }

    @Test
    @DisplayName("accepted(..., credential) ⇒ UNKNOWN + 带凭证（INV-6：钱未到，不得收敛）")
    void acceptedWithCredentialStaysUnknownAndCarriesCredential() {
        PayCredential cred = PayCredential.redirectUrl("https://pay.example/x", null);
        ChannelResult r = ChannelResult.accepted(null, "awaiting buyer", cred);

        // 语义 = 通信成功 + 业务无结论：绝不因「拿到凭证」就当成成功
        assertThat(r.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(r.hasCredential()).isTrue();
        assertThat(r.credential()).isSameAs(cred);
        assertThat(r.transportCode()).isEqualTo(TransportCode.SUCCESS);
        assertThat(r.businessCode()).isEqualTo(BusinessCode.UNKNOWN);
    }

    @Test
    @DisplayName("FR-112：withReason 保留 credential（重试耗尽标注不得抹掉凭证）")
    void withReasonPreservesCredential() {
        PayCredential cred = PayCredential.redirectUrl("https://pay.example/x", null);
        ChannelResult r = ChannelResult.accepted(null, "awaiting buyer", cred);

        ChannelResult retried = r.withReason("RETRY_EXHAUSTED");

        assertThat(retried.reason()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(retried.credential()).isSameAs(cred);
        assertThat(retried.hasCredential()).isTrue();
        // 其余字段口径不变
        assertThat(retried.status()).isEqualTo(r.status());
        assertThat(retried.transportCode()).isEqualTo(r.transportCode());
        assertThat(retried.businessCode()).isEqualTo(r.businessCode());
    }

    @Test
    @DisplayName("withReason 对无凭证结果同样保持 credential=null")
    void withReasonOnCredentialFreeResultStaysNull() {
        ChannelResult r = ChannelResult.timeout("t");
        assertThat(r.withReason("RETRY_EXHAUSTED").credential()).isNull();
    }
}
