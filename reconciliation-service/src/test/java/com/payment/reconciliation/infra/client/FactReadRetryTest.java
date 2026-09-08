package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Retryer;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事实读取重试（spec 006 T030 / ADR-0021）：仅对幂等只读 GET 做有限重试——
 * 瞬时 5xx 重试后成功；持续 5xx 耗尽后失败，且错误被归一化为 {@code INTERNAL_ERROR}。
 *
 * <p>用 {@link Client} 替身计数，不起真实 HTTP 服务（无 WireMock 依赖）：验证的是
 * 「重试器 + 错误解码器」的组合行为，与 {@link FactsClientConfig} 生产配置同源。</p>
 */
class FactReadRetryTest {

    private static final String URL = "http://payment-service/internal/payments/confirmed-facts";

    private Request request() {
        return Request.create(Request.HttpMethod.GET, URL, Map.of(), null,
                StandardCharsets.UTF_8, new RequestTemplate());
    }

    private Response http(int status) {
        return Response.builder()
                .status(status)
                .reason("stub")
                .request(request())
                .headers(Collections.emptyMap())
                .build();
    }

    /**
     * feign 原生契约声明（{@code Feign.builder()} 默认解析器不认 Spring 的 {@code @GetMapping}）。
     * 被测的重试器与错误解码器与生产同源（均取自 {@link FactsClientConfig}），换成原生契约
     * 不影响「重试几次 / 失败归一化成什么」这一被测语义。
     */
    interface FactsApi {
        @feign.RequestLine("GET /internal/payments/confirmed-facts")
        List<PaymentFactDto> fetchConfirmedFacts();
    }

    private FactsApi client(Client delegate, Retryer retryer, ErrorDecoder decoder) {
        Decoder bodyDecoder = (response, type) -> List.of(
                new PaymentFactDto("PM1", "CH1", 1000L, "CNY", "SUCCEEDED"));
        return Feign.builder()
                .client(delegate)
                .retryer(retryer)
                .errorDecoder(decoder)
                .decoder(bodyDecoder)
                .target(FactsApi.class, "http://payment-service");
    }

    private Retryer productionRetryer() {
        return new FactsClientConfig(new MockEnvironment()).retryer();
    }

    private ErrorDecoder productionErrorDecoder() {
        return new FactsClientConfig(new MockEnvironment()).errorDecoder();
    }

    @Test
    void transientFailureIsRetriedUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        Client flaky = (request, options) -> {
            int n = calls.incrementAndGet();
            return n < 3 ? http(500) : http(200);
        };

        List<PaymentFactDto> facts = client(flaky, productionRetryer(), productionErrorDecoder())
                .fetchConfirmedFacts();

        assertThat(calls.get()).isEqualTo(3);
        assertThat(facts).hasSize(1);
    }

    /**
     * 持续 503：重试耗尽后 Feign 抛出 {@link feign.RetryableException}，由
     * {@code FeignPaymentFactsClient} 归一化为 {@code INTERNAL_ERROR}（此处直接断言 Feign 层语义）。
     */
    @Test
    void persistentFailureExhaustsRetryAndIsNormalized() {
        AtomicInteger calls = new AtomicInteger();
        Client alwaysDown = (request, options) -> {
            calls.incrementAndGet();
            return http(503);
        };

        assertThatThrownBy(() -> client(alwaysDown, productionRetryer(), productionErrorDecoder())
                .fetchConfirmedFacts())
                .isInstanceOf(feign.RetryableException.class)
                .hasMessageContaining("facts read failed");
        // Retryer.Default(period=1000, maxPeriod=2000, maxAttempts=3)：首调 + 2 次重试 = 3 次请求
        assertThat(calls.get()).isEqualTo(3);
    }

    /** 4xx（非 408/429）不可重试：写语义与参数错误重试无意义。 */
    @Test
    void clientErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        Client badRequest = (request, options) -> {
            calls.incrementAndGet();
            return http(400);
        };

        assertThatThrownBy(() -> client(badRequest, productionRetryer(), productionErrorDecoder())
                .fetchConfirmedFacts())
                .isInstanceOf(BizException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void successIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        Client ok = (request, options) -> {
            calls.incrementAndGet();
            return Response.builder()
                    .status(200)
                    .reason("ok")
                    .request(request())
                    .headers(Map.of("Content-Type", List.of("application/json")))
                    .body("[]", StandardCharsets.UTF_8)
                    .build();
        };

        client(ok, productionRetryer(), productionErrorDecoder()).fetchConfirmedFacts();

        assertThat(calls.get()).isEqualTo(1);
    }
}
