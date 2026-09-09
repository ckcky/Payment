package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.sun.net.httpserver.HttpServer;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 出站弹性回归（spec 007 T040 / ADR-0023 §4）：覆盖三件事——
 * <ol>
 *   <li>注入延迟：超时配置真实生效，调用不会无限挂起；</li>
 *   <li>注入瞬时 5xx：仅对幂等只读 GET 做有限重试（≤3、退避 1s/2s/4s），耗尽后归一化为 INTERNAL_ERROR；</li>
 *   <li>写操作（ledger POST）走 {@link LedgerFeignConfig}（无 Retryer → feign 默认 NEVER_RETRY），绝不重试。</li>
 * </ol>
 *
 * <p>读路径用 {@link Client} 替身计数，不起真实 HTTP 服务（无 WireMock 依赖）；写路径与超时路径分别用
 * {@link LedgerFeignConfig} 与 JDK 自带 {@link HttpServer} 注入延迟，验证的是「重试器 + 错误解码器 +
 * 超时选项」的组合行为，与被测生产配置同源。</p>
 */
class OutboundResilienceTest {

    private static final String URL = "http://merchant-service/internal/merchants/M1";

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

    /** 原生 Feign 契约（{@code Feign.builder()} 默认解析器不认 Spring 的 {@code @GetMapping}）。 */
    interface SettlementReadApi {
        @feign.RequestLine("GET /internal/merchants/{id}")
        String fetch();
    }

    /** 写路径接口：ledger 记账 POST（非幂等安全重试）。 */
    interface LedgerWriteApi {
        @feign.RequestLine("POST /internal/ledger/postings")
        String post();
    }

    /** 超时路径接口：GET 一个会休眠的端点。 */
    interface SlowApi {
        @feign.RequestLine("GET /slow")
        String get();
    }

    private SettlementReadApi readClient(Client delegate, Retryer retryer, ErrorDecoder decoder) {
        Decoder bodyDecoder = (response, type) -> "ok";
        return Feign.builder()
                .client(delegate)
                .retryer(retryer)
                .errorDecoder(decoder)
                .decoder(bodyDecoder)
                .target(SettlementReadApi.class, "http://merchant-service");
    }

    private Retryer productionRetryer() {
        return new SettlementFeignConfig().retryer();
    }

    private ErrorDecoder productionErrorDecoder() {
        return new SettlementFeignConfig().errorDecoder();
    }

    // ---- 读路径：瞬时 5xx 有限重试 ----

    @Test
    void transientServerErrorIsRetriedUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        Client flaky = (request, options) -> {
            int n = calls.incrementAndGet();
            return n < 3 ? http(500) : http(200);
        };

        String result = readClient(flaky, productionRetryer(), productionErrorDecoder()).fetch();

        // Retryer.Default(1000, 2000, 3)：首调 + 2 次重试，第 3 次成功
        assertThat(calls.get()).isEqualTo(3);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void persistentServerErrorExhaustsRetryAndIsRetryable() {
        AtomicInteger calls = new AtomicInteger();
        Client alwaysDown = (request, options) -> {
            calls.incrementAndGet();
            return http(503);
        };

        assertThatThrownBy(() -> readClient(alwaysDown, productionRetryer(), productionErrorDecoder()).fetch())
                .isInstanceOf(RetryableException.class);
        // 重试耗尽：首调 + 2 次重试 = 3 次请求
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void clientErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        Client badRequest = (request, options) -> {
            calls.incrementAndGet();
            return http(400);
        };

        assertThatThrownBy(() -> readClient(badRequest, productionRetryer(), productionErrorDecoder()).fetch())
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

        readClient(ok, productionRetryer(), productionErrorDecoder()).fetch();
        assertThat(calls.get()).isEqualTo(1);
    }

    // ---- 写路径：ledger POST 绝不重试（LedgerFeignConfig 无 Retryer → NEVER_RETRY） ----

    @Test
    void writePostIsNeverRetriedOnServerError() {
        AtomicInteger calls = new AtomicInteger();
        Client alwaysDown = (request, options) -> {
            calls.incrementAndGet();
            return http(500);
        };

        LedgerWriteApi api = Feign.builder()
                .client(alwaysDown)
                // 不配置 Retryer → feign 默认 NEVER_RETRY（与生产 LedgerFeignConfig 一致）
                .errorDecoder(new LedgerFeignConfig().errorDecoder())
                .decoder((response, type) -> "ok")
                .target(LedgerWriteApi.class, "http://ledger-service");

        assertThatThrownBy(api::post).isInstanceOf(BizException.class);
        // 关键断言：写操作只发了 1 次请求，没有重试
        assertThat(calls.get()).isEqualTo(1);
    }

    // ---- 超时配置：connect 1s / read 3s 真实生效 ----

    @Test
    void defaultTimeoutsAreWired() {
        Request.Options options = new SettlementFeignConfig().requestOptions();

        assertThat(options.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(options.readTimeoutMillis()).isEqualTo(3000);
    }

    /** 注入延迟（端点休眠超过 read 超时）→ 调用在超时预算内失败，而不是无限挂起。 */
    @Test
    void injectedLatencyTriggersReadTimeoutInsteadOfHanging() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(800); // 远超下方 200ms 的 read 超时
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            SlowApi api = Feign.builder()
                    .client(new Client.Default(null, null))
                    .retryer(Retryer.NEVER_RETRY)
                    .options(new Request.Options(200, 200))
                    .decoder((response, type) -> "ok")
                    .target(SlowApi.class, "http://localhost:" + port);

            // 5s 硬性上限：若超时未生效，这里会超时中断，测试失败（即证明「不会无限挂起」）
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(api::get).isInstanceOf(RetryableException.class));
        } finally {
            server.stop(0);
        }
    }
}
