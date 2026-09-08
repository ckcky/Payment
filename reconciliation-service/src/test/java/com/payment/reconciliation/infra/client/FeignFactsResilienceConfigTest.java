package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import feign.Request;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事实读取 RPC 弹性配置绑定（spec 006 T032 / T035 / ADR-0021）：
 * 超时阈值可被 {@code services.payment.*} 覆盖，缺省回落 connect 1s / read 3s；
 * 错误解码器把下游任何状态码归一化为 {@code INTERNAL_ERROR}（不外泄基础设施细节）。
 */
class FeignFactsResilienceConfigTest {

    @Test
    void fallsBackToDefaultTimeoutsWhenPropertiesAbsent() {
        Request.Options options = new FactsClientConfig(new MockEnvironment()).requestOptions();

        assertThat(options.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(options.readTimeoutMillis()).isEqualTo(3000);
    }

    @Test
    void timeoutsAreBoundFromProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("services.payment.connect-timeout-ms", "1500")
                .withProperty("services.payment.read-timeout-ms", "4200");

        Request.Options options = new FactsClientConfig(environment).requestOptions();

        assertThat(options.connectTimeoutMillis()).isEqualTo(1500);
        assertThat(options.readTimeoutMillis()).isEqualTo(4200);
    }

    @Test
    void malformedTimeoutFallsBackToDefault() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("services.payment.read-timeout-ms", "not-a-number");

        Request.Options options = new FactsClientConfig(environment).requestOptions();

        assertThat(options.readTimeoutMillis()).isEqualTo(3000);
    }

    @Test
    void retryerIsLimited() {
        Retryer retryer = new FactsClientConfig(new MockEnvironment()).retryer();

        assertThat(retryer).isNotNull();
        assertThat(retryer).isInstanceOf(Retryer.Default.class);
    }

    /** 5xx / 408 / 429 属可重试：必须抛 {@code RetryableException}，否则重试器不生效。 */
    @Test
    void errorDecoderMarksServerErrorsAsRetryable() {
        ErrorDecoder decoder = new FactsClientConfig(new MockEnvironment()).errorDecoder();

        Exception decoded = decoder.decode("PaymentFactsFeignClient#fetchConfirmedFacts()", response(503));

        assertThat(decoded).isInstanceOf(feign.RetryableException.class);
        assertThat(decoded).hasMessageContaining("facts read failed");
    }

    /** 其余 4xx 不可重试：直接归一化为 {@code INTERNAL_ERROR}（不外泄状态码细节）。 */
    @Test
    void errorDecoderNormalizesClientErrorsToInternalError() {
        ErrorDecoder decoder = new FactsClientConfig(new MockEnvironment()).errorDecoder();

        Exception decoded = decoder.decode("PaymentFactsFeignClient#fetchConfirmedFacts()", response(400));

        assertThat(decoded).isInstanceOf(BizException.class);
        assertThat(decoded).hasMessageContaining("facts read failed");
    }

    @Test
    void retryableStatusCodes() {
        assertThat(FactsClientConfig.isRetryable(408)).isTrue();
        assertThat(FactsClientConfig.isRetryable(429)).isTrue();
        assertThat(FactsClientConfig.isRetryable(500)).isTrue();
        assertThat(FactsClientConfig.isRetryable(503)).isTrue();
        assertThat(FactsClientConfig.isRetryable(400)).isFalse();
        assertThat(FactsClientConfig.isRetryable(404)).isFalse();
    }

    private feign.Response response(int status) {
        return feign.Response.builder()
                .status(status)
                .reason("stub")
                .request(Request.create(Request.HttpMethod.GET, "/internal/payments/facts",
                        java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8,
                        new RequestTemplateHolder().template()))
                .build();
    }

    /** 构造解码所需的 {@link feign.RequestTemplate}（仅测试用，无业务含义）。 */
    private static final class RequestTemplateHolder {
        feign.RequestTemplate template() {
            return new feign.RequestTemplate();
        }
    }
}
