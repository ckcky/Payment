package com.payment.order.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.core.error.BizException;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * order → payment 出站 RPC 的错误保真解码器（spec 028 / FR-045 收尾）。
 *
 * <p><b>为什么需要它</b>：payment-service 对路由类失败给出语义明确的状态码
 * （{@code 409 CHANNEL_UNAVAILABLE}、{@code 409 NO_AVAILABLE_CHANNEL}、{@code 400 INVALID_ARGUMENT}）。
 * 但 Feign 默认的 {@code ErrorDecoder} 抛 {@code FeignException}，order-service 的
 * {@code GlobalExceptionHandler} 只把它当未知异常 → <b>一律 500</b>。结果是调用方看到「服务器内部错误」，
 * 而真实原因是「你指定的渠道不可用」——语义完全丢失，且演示脚本 S4/G4 无法断言 409。</p>
 *
 * <p><b>做法</b>：解析下游错误体里的 {@code code}/{@code message}，还原为
 * {@link BizException}，让 {@code GlobalExceptionHandler} 按既有 {@code mapStatus} 映射回同义状态码。
 * 无法解析（非 JSON / 5xx / 网络层）时回落到默认解码器，保持原行为不变。</p>
 *
 * <p>不注册为全局 Bean（刻意不标 {@code @Component}/{@code @Configuration}），
 * 只通过 {@code @FeignClient(configuration = ...)} 绑定到 payment 客户端，
 * 避免污染其他 Feign 客户端的错误语义。</p>
 */
public class PaymentFeignConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 保真解码：下游 4xx 且响应体含已知 {@code code} → 还原为 {@link BizException}（状态码同义保留）；
     * 其余（含 5xx / 非 JSON / 网络层）交由默认解码器处理。
     */
    @Bean
    public ErrorDecoder errorDecoder() {
        ErrorDecoder fallback = new ErrorDecoder.Default();
        return (methodKey, response) -> {
            int status = response.status();
            // 只保真 4xx：5xx 是服务端故障，继续走默认路径（避免把基础设施错误伪装成业务拒绝）
            if (status >= 400 && status < 500) {
                String body = extractBody(response);
                BizException preserved = toBizException(body);
                if (preserved != null) {
                    return preserved;
                }
            }
            return fallback.decode(methodKey, response);
        };
    }

    private static String extractBody(Response response) {
        if (response.body() == null) {
            return null;
        }
        try {
            return Util.toString(response.body().asReader(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 {@code {"code":"CHANNEL_UNAVAILABLE","message":"..."}} 还原业务异常；不可解析则 null。 */
    private static BizException toBizException(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            String code = node.path("code").asText(null);
            if (code == null || code.isBlank()) {
                return null;
            }
            String message = node.path("message").asText("downstream rpc failed");
            return new BizException(code, message);
        } catch (IOException e) {
            return null;
        }
    }
}
