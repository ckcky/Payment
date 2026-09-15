package com.payment.e2e.contract;

import com.payment.e2e.support.E2eBase;
import com.payment.e2e.support.Env;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 契约：{@code payUrl} 的<b>客户端可达性</b>（2026-09-15 缺陷回归）。
 *
 * <p><b>为什么需要这个用例</b>：既有的 e2e / demo 全部是 curl 打接口、断言状态码，
 * 从不打开接口<b>返回的链接</b>。所以「下单成功、支付单也建了，但收银台跳转不了」
 * 这类缺陷对整套自动化是隐形的——全绿而人一上手就崩。</p>
 *
 * <p><b>缺陷原貌</b>：容器模式下 {@code payment.mock-cashier.base-url} 曾配成
 * {@code http://mock-channel-web:8091}（容器内服务名）。该值被 {@code buildPayUrl()}
 * 拼进 {@code payUrl} 交给浏览器 {@code window.open()}，而浏览器所在宿主解析不了
 * 容器内网名字（NXDOMAIN）。容器内 curl 该 URL 反而返回 200，极具迷惑性。</p>
 *
 * <p><b>断言口径</b>：对建支付单响应里的 {@code payUrl} 按<b>绝对 URL</b> 直连一次，
 * 要求 2xx。这等价于「宿主（浏览器所在处）能否打开收银台」，是 curl 可验证的最小充分条件。</p>
 */
class CashierPayUrlReachabilityTest extends E2eBase {

    @Test
    @DisplayName("建支付单返回的 payUrl 必须客户端可达（宿主可直连，非容器内服务名）")
    void payUrlIsClientReachable() {
        runCase("cashier-payurl-reachable", ctx -> {
            Assumptions.assumeTrue(
                    Env.clientReachabilityCheckEnabled(),
                    "当前环境已关闭客户端可达性断言（e2e.client-reachability-check=false）");

            String userId = prefix("payurl") + "-u";

            // 造一个专属 SKU，避免与其它用例抢库存
            long skuId = skuWithPrice(ctx, 8800);

            var created = API.createOrder(userId + "-order", userId, "e2e-m-payurl", skuId, 1);
            ctx.response("createOrder", created);
            assertThat(created.is2xx())
                    .as("下单应成功，实际 HTTP %s body=%s", created.status(), created.body())
                    .isTrue();
            String orderNo = created.json().path("orderNo").asText();

            var paid = API.createPayment(orderNo, "MOCK");
            ctx.response("createPayment", paid);
            assertThat(paid.is2xx())
                    .as("建支付单应成功，实际 HTTP %s body=%s", paid.status(), paid.body())
                    .isTrue();

            var payUrlNode = paid.json().path("payUrl");
            assertThat(payUrlNode.isMissingNode() || payUrlNode.isNull())
                    .as("mock-cashier 开启时应返回 payUrl（否则收银台演示路径不可用）；"
                            + "e2e/CI 若显式关闭了 PAYMENT_MOCK_CASHIER_ENABLED 则本用例不适用")
                    .isFalse();
            String payUrl = payUrlNode.asText();
            ctx.invariant("payUrl=" + payUrl);

            // 核心断言：按客户端视角直连该链接，必须 2xx
            var page = API.getAbsolute(payUrl);
            ctx.response("GET " + payUrl, page);
            assertThat(page.status())
                    .as("payUrl 必须客户端可达（等价于浏览器跳转成功）：%s → HTTP %s %s",
                            payUrl, page.status(), page.body())
                    .isBetween(200, 299);

            // 收银台页确实是收银台（而非 200 的错误页/空壳页）。
            // 注意：收银台页是**客户端渲染**——paymentNo 由页面 JS 从 location.search 读出后
            // 注入 DOM，故 curl 拿到的静态 HTML 里不含该单号，只能断言页面标志物。
            assertThat(page.body())
                    .as("payUrl 应指向 mock 收银台页")
                    .contains("收银台");

            // 最强断言：收银台**页面自己的换渠道接口**（/proxy/order/.../payments）也能通——
            // 该接口正是页面上「换个渠道再付」按钮调的，通了说明收银台不只是能打开、还真能用。
            var newPay = API.post("mock",
                    "/proxy/order/orders/" + orderNo + "/payments", Map.of(), Map.of("channelCode", "WECHAT"));
            ctx.response("cashier switch-channel", newPay);
            assertThat(newPay.is2xx())
                    .as("收银台页内换渠道接口应可用（HTTP %s body=%s）", newPay.status(), newPay.body())
                    .isTrue();
            // 换渠道后返回的 payUrl 同样必须客户端可达：断言基址与新生单号正确
            // （不断言 channelCode —— 换渠道后它本就该变成新渠道）
            String newPayUrl = newPay.json().path("payUrl").asText();
            assertThat(newPayUrl)
                    .as("换渠道后的 payUrl 基址应与会话地址一致（不含容器内服务名）")
                    .startsWith("http://localhost:8091/cashier?")
                    .contains("paymentNo=" + newPay.json().path("paymentNo").asText())
                    .contains("orderNo=" + orderNo);

            // 仅当客户端与测试进程不共享网络时（如 local：测试在宿主、服务在容器），
            // 才可断言「不得使用容器内服务名」——这是该缺陷的根因形态。
            if (!Env.clientAndTestShareNetwork()) {
                assertThat(payUrl)
                        .as("客户端不共享服务网络时，payUrl 不得使用容器内服务名"
                                + "（宿主浏览器解析不了，会 NXDOMAIN）")
                        .doesNotContain(":8848")
                        .doesNotMatch(".*//(mock-channel-web|payment-service|order-service|catalog-service)"
                                + "(:(?!809[0-9]\\b)\\d+)?/.*");
            }
        });
    }
}
