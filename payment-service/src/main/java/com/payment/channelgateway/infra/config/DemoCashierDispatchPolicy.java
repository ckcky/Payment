package com.payment.channelgateway.infra.config;

import com.payment.channelgateway.application.ChargeDispatchPolicy;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.dto.channel.PayCredential;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 演示收银台派发策略（spec 041 / FR-021）：把「mock 环境判断」收进渠道网关域。
 *
 * <h3>裁决口径（与改造前逐字等价）</h3>
 * <pre>
 *   延迟 ⇔ payment.mock-cashier.enabled == true 且 当前请求非 SANDBOX 染色
 * </pre>
 * <p>改造前这段判断写在 {@code PaymentController#createPayment}：
 * {@code boolean defer = mockCashier.isEnabled() && !channelGateway.isSandboxRequest();}
 * 两个分量各自都属渠道域（一个是渠道演示开关、一个是流量染色），
 * 却由资金动作域的 Controller 拼装——本类把它们一并收回。</p>
 *
 * <h3>为什么沙箱<b>不</b>延迟</h3>
 * <p>沙箱链路的意义是「真的调渠道、真的拿凭证」。延迟就不调 charge，
 * 不调 charge 就拿不到凭证，沙箱闭环直接断掉（spec 030 / FR-167）。</p>
 *
 * <h3>为什么不按渠道码区分</h3>
 * <p>改造前该判断对<b>所有</b>渠道生效（{@code defer} 不区分渠道码），
 * 本策略同样不区分——零行为变化优先。若将来要「仅对 mock 系渠道生效」，
 * 那是独立决策，须另立 ADR，不得在此夹带。</p>
 */
@Component
public class DemoCashierDispatchPolicy implements ChargeDispatchPolicy {

    private final MockCashierProperties properties;

    public DemoCashierDispatchPolicy(MockCashierProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<PayCredential> deferredCredential(ChargeRequest request) {
        if (!properties.isEnabled() || DyeContext.isSandbox()) {
            return Optional.empty();
        }
        return Optional.of(PayCredential.redirectUrl(cashierUrl(request), null));
    }

    /**
     * 收银台页链接：mock-channel-web 的 {@code /cashier}，页面从查询串自渲染。
     *
     * <p>构造口径与改造前 {@code PaymentController#buildPayUrl} 逐字一致
     * （含 {@code channelCode} 缺省回落 {@code MOCK}），保证演示页与既有断言零变化。</p>
     */
    private String cashierUrl(ChargeRequest request) {
        String channelCode = request.channelCode() == null || request.channelCode().isBlank()
                ? "MOCK" : request.channelCode();
        String orderNo = request.orderNo() == null ? "" : request.orderNo();
        return properties.getBaseUrl() + "/cashier?paymentNo=" + request.paymentNo()
                + "&orderNo=" + orderNo
                + "&amountMinor=" + request.amountMinor()
                + "&currencyCode=" + request.currencyCode()
                + "&channelCode=" + channelCode;
    }
}
