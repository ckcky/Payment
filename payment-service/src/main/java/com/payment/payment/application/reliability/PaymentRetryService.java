package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.channelgateway.application.ChannelGateway;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.PaymentChannel;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 请求内联重试（spec US3 / FR-005~FR-007 / ADR-0012~0014 修订版）。
 *
 * <p>规则：
 * <ul>
 *   <li><b>可重试性只看通信响应码</b>（{@link ChannelResult#retryable()}）：
 *       {@code TransportCode != SUCCESS} 即重试，<b>超时算通信失败</b>（含 {@code TIMEOUT}）。</li>
 *   <li>业务响应码非 SUCCESS（通信成功但被拒）→ <b>不重试</b>，直接进 {@code FAILED}（FR-006）。</li>
 *   <li>重试在<b>本次请求线程内</b>同步退避重放，<b>不落库、不进调度队列</b>（ADR-0013 修订）。</li>
 *   <li>重试在<b>同一 attempt</b> 上重放（幂等键不变），不创建新 attempt（ADR-0014）。</li>
 *   <li>重试耗尽且结果仍不确定 → 结果保持 {@code UNKNOWN}、reason 记为
 *       {@value #EXHAUSTED_REASON}，发 {@code payment.retry_exhausted} 后由 US2 主动查询收敛，
 *       <b>绝不臆断成败</b>（FR-007）。</li>
 * </ul>
 * 已进入 UNKNOWN 的支付不在本类重试——那属于主动查询收敛（{@code ChannelQueryService}）的职责。</p>
 *
 * <p><b>渠道来源（Feature 028 / FR-024 / INV-6）</b>：本类<b>不持有渠道单例</b>——每次按
 * {@link ChargeRequest#channelCode()}（已由调用方按路由结果或 attempt 记录定好）经
 * {@link ChannelGateway} 精确解析。<b>绝不调 Router</b>：重试必须回到原来那个渠道，
 * 换个渠道重试等于用别人的流水号去扣款。</p>
 */
@Service
public class PaymentRetryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryService.class);

    private static final String MODULE = "payment";
    public static final String EXHAUSTED_REASON = "RETRY_EXHAUSTED";

    private final ChannelGateway channelGateway;
    private final ReliabilityConfig config;
    private final BusinessMetrics metrics;

    /** 生产主构造：Spring 必须确定地选它（另有测试用兼容构造，故显式标注）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentRetryService(ChannelGateway channelGateway, ReliabilityConfig config, BusinessMetrics metrics) {
        this.channelGateway = channelGateway;
        this.config = config;
        this.metrics = metrics;
    }

    /**
     * 兼容构造（Feature 028 / FR-036 / SC-012）：保留既有「单通道」签名，
     * 内部包装为「单通道门面」——既有测试零改动。
     */
    public PaymentRetryService(PaymentChannel singleChannel, ReliabilityConfig config, BusinessMetrics metrics) {
        this(ChannelGateway.ofSingleChannel(singleChannel), config, metrics);
    }

    /**
     * 调用渠道并在通信失败时内联重放，返回最终结果与本次实际重试次数。
     *
     * <p>重试期间不写数据库；调用方在最终收敛时把 {@code retries} 一并落库（单次写）。</p>
     *
     * <p>渠道按 {@code request.channelCode()} 解析（INV-6）；同一 attempt 的重放必然用同一渠道
     * ——{@code channelCode} 在本次调用内不变。</p>
     *
     * <p><b>凭证透传（spec 030 / FR-114 · T29）</b>：返回的 {@code result} 恒为<b>最后一次</b>
     * {@code charge} 的结果，因此天然携带最后一次渠道调用给出的 {@code credential}；重试耗尽
     * 路径上的 {@link ChannelResult#withReason(String)} 亦<b>保留</b> credential（FR-112）。
     * 于是调用方拿到的 {@link RetryOutcome} 永远能回答「买家去哪儿付款」——凭证不会因为
     * 改 reason 或重放次数而被抹掉。</p>
     */
    public RetryOutcome chargeWithRetry(ChargeRequest request) {
        // spec 037 / T4：解析与调用都经门面。渠道码先算好（含兼容路径回落唯一注册渠道），
        // 再交给门面按该码精确解析——门面不选路，重放必然回到同一个渠道（INV-6 / ADR-0014）。
        String channelCode = channelCodeOf(request);
        ChannelResult result = channelGateway.pay(channelCode, request);
        int retries = 0;
        while (result.retryable() && (retries + 1) < config.getRetryMaxAttempts()) {
            Duration backoff = backoffFor(retries);
            log.warn("渠道通信失败（{}），第 {} 次重试，退避 {}ms: {}",
                    result.transportCode(), retries + 1, backoff.toMillis(), result.reason());
            sleep(backoff);
            metrics.counter("payment.retry", 1.0, "module", MODULE);
            result = channelGateway.pay(channelCode, request); // 同一 attempt 重放，幂等键不变（ADR-0014）
            retries++;
        }
        if (result.retryable()) {
            metrics.counter("payment.retry_exhausted", 1.0, "module", MODULE);
            log.warn("渠道通信失败重试耗尽（{} 次调用，上限 {}），支付进 UNKNOWN 待主动查询收敛: {}",
                    retries + 1, config.getRetryMaxAttempts(), result.transportCode());
            return new RetryOutcome(result.withReason(EXHAUSTED_REASON), retries);
        }
        return new RetryOutcome(result, retries);
    }

    /** 一次渠道调用的最终结果与实际重试次数（重试次数由调用方在收敛时落库）。 */
    public record RetryOutcome(ChannelResult result, int retries) {
    }

    /**
     * 取本次调用要用的渠道码（INV-6）。
     *
     * <p>正常路径由调用方在 {@link ChargeRequest#channelCode()} 里给定（路由结果或 attempt 记录）。
     * 兼容路径（FR-036）：单通道注册表下请求可能不带渠道码，此时回落到注册表唯一渠道码
     * ——不引入任何默认值常量，渠道仍由注册表决定。</p>
     */
    private String channelCodeOf(ChargeRequest request) {
        String code = request == null ? null : request.channelCode();
        if (code != null && !code.isBlank()) {
            return code;
        }
        java.util.Set<String> registered = channelGateway.registeredChannelCodes();
        if (registered.size() == 1) {
            return registered.iterator().next();
        }
        throw com.payment.common.core.error.BizException.of(
                com.payment.common.core.error.ErrorCodes.INVALID_ARGUMENT,
                "charge request carries no channelCode and the registry holds "
                        + registered.size() + " channels; channelCode is required: " + registered);
    }

    private void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry interrupted", e);
        }
    }

    /** 第 {@code retryIndex} 次重试的退避时长（越界取序列最后一个）。 */
    private Duration backoffFor(int retryIndex) {
        List<Duration> backoff = config.getRetryBackoff();
        if (backoff == null || backoff.isEmpty()) {
            return Duration.ofSeconds(1);
        }
        return backoff.get(Math.min(retryIndex, backoff.size() - 1));
    }
}
