package com.payment.channelgateway.api;

import com.payment.channelgateway.application.ChannelCallbackAck;
import com.payment.channelgateway.application.ChannelCallbackHandler;
import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 渠道异步回调的<b>通用插件端点</b>（渠道插件化内核 / SPI-09）：
 * {@code POST /internal/channels/{channelCode}/callback}。
 *
 * <h3>本类只做两件事（spec 037 / T5 收口后）</h3>
 * <ol>
 *   <li><b>收报文</b>：把 HTTP 请求<b>原样搬运</b>成 {@link ChannelCallbackEnvelope}
 *       （原始字节 + 表单参数 + 小写头），<b>不做任何渠道语义解析</b>——不读
 *       {@code out_trade_no}、不读 {@code data.object.id}。内核一旦认识某个字段名，
 *       第二家渠道就得改内核。</li>
 *   <li><b>交网关</b>：把信封交给渠道网关域的 {@link ChannelCallbackHandler}
 *       （四步模板方法），再把应答转成 HTTP。</li>
 * </ol>
 *
 * <p>改造前本类还承担了<b>Payment 的业务校验</b>（查 payment、验渠道引用归属、验金额币种）
 * 与拒绝三件套——那是把资金域的规则写进了渠道域的入口。T5 按 FR-011 把这段逻辑迁入
 * {@code PaymentNotifyPort} 的实现，本类因此不再依赖任何 Payment 侧类型。</p>
 *
 * <h3>为什么路径里带 {@code channelCode}（INV-6）</h3>
 * <p>回调必须<b>精确寻址</b>到「当初那笔交互走的渠道」，绝不重新路由。
 * 路径变量天然满足这一点：谁的通知进谁的门。
 * 资金安全红线：退款/通知换渠道 = 钱处理到错的地方。</p>
 *
 * <h3>为什么 body 只被读取一次</h3>
 * <p>先读原始字节（Stripe 验签必须用原始字节，重新序列化 JSON 会改变签名基串），
 * 再按 content-type 决定是否需要解析成表单。若改用 {@code @RequestParam}，
 * 容器会先消费 body，JSON 形态的渠道将拿不到原始报文。</p>
 *
 * <h3>与既有端点的关系（迁移中状态）</h3>
 * <ul>
 *   <li>{@link ChannelCallbackController}（{@code /internal/payments/{paymentNo}/channel-callback}）：
 *       <b>平台内部</b>的 mock 回调入口，非渠道协议，保持不动；</li>
 *   <li>{@code AlipayNotifyController}：支付宝专属端点，既有测试与联调脚本依赖它，
 *       迁移到本端点后再删除。两条路径并存是<b>刻意登记的技术债</b>，见《Channel 域架构评估》。</li>
 * </ul>
 */
@RestController
public class ChannelPluginCallbackController {

    private static final String FORM_CONTENT_TYPE = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    private final ChannelCallbackHandler handler;

    public ChannelPluginCallbackController(ChannelCallbackHandler handler) {
        this.handler = handler;
    }

    /** 接收渠道异步回调：收报文 → 交网关 → 转 HTTP。 */
    @PostMapping("/internal/channels/{channelCode}/callback")
    public ResponseEntity<String> onCallback(@PathVariable String channelCode,
                                             HttpServletRequest request) throws IOException {
        ChannelCallbackEnvelope envelope = toEnvelope(request);
        ChannelCallbackAck ack = handler.handle(channelCode, envelope);
        // 验签失败 ⇒ 403（报文真伪未定，不触达任何状态推进，INV-10）；
        // 其余一律 200，靠 body 表达语义——渠道（如支付宝）靠 body 精确匹配判断「已收到」。
        return ack.signatureVerified()
                ? ResponseEntity.ok(ack.body())
                : ResponseEntity.status(HttpStatus.FORBIDDEN).body(ack.body());
    }

    /** HTTP 请求 → 原始信封（原始字节 + 表单参数 + 小写头；内核不做渠道语义解析）。 */
    private static ChannelCallbackEnvelope toEnvelope(HttpServletRequest request) throws IOException {
        byte[] raw = StreamUtils.copyToByteArray(request.getInputStream());
        String body = new String(raw, StandardCharsets.UTF_8);
        String contentType = request.getContentType();
        Map<String, String> form = contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(FORM_CONTENT_TYPE)
                ? parseForm(body)
                : Map.of();
        return new ChannelCallbackEnvelope(lowerCaseHeaders(request), form, body);
    }

    /** 头名统一小写——渠道签名头大小写不保证（{@code Stripe-Signature} / {@code stripe-signature}）。 */
    private static Map<String, String> lowerCaseHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }

    /** {@code a=1&b=2} → Map（值按 UTF-8 解码）。 */
    private static Map<String, String> parseForm(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return params;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
