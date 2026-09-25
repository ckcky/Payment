package com.payment.channelgateway.infra.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.common.dto.channel.PaymentScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 039 / T2：报文构造与协议映射（INV-4 金额单位、FR-004 端点分派、FR-006 退款单号、spec §8 状态映射）。
 *
 * <p>这些断言<b>不需要 HTTP</b>——直接检查包内可见的报文构造器与映射纯函数。
 * 网络层（仿真桩全链路）由 T5 覆盖。</p>
 */
class WechatSdkGatewayAmountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 未配私钥 ⇒ signer 为 null；但报文构造 / 场景分派 / 映射不需要签名。 */
    private static WechatSdkGateway gateway() {
        WechatPayProperties p = new WechatPayProperties();
        p.setMchId("1900000109");
        p.setAppId("wx1234567890abcdef");
        p.setNotifyUrl("https://example.com/internal/channels/WECHAT/callback");
        return new WechatSdkGateway(p);
    }

    private static WechatGateway.PrepayCommand nativeCommand(long amountMinor) {
        return new WechatGateway.PrepayCommand(PaymentScene.NATIVE, "PM001", amountMinor, "CNY",
                "测试商品", "https://example.com/notify", null, null, null);
    }

    // ---- INV-4：金额单位（分直传，禁止 *100 / /100） ----

    @Test
    @DisplayName("amount.total 直接等于 amountMinor（分），无任何换算 [INV-4][FR-004]")
    void amountTotalIsMinorUnitsVerbatim() throws Exception {
        WechatSdkGateway gateway = gateway();

        // 1 分必须写 1，不能写 100；100 分必须写 100，不能写 1 或 10000
        for (long minor : new long[]{1L, 100L, 12_345L, 999_999L}) {
            JsonNode body = MAPPER.readTree(gateway.buildPrepayBody(nativeCommand(minor)));
            assertThat(body.get("amount").get("total").asLong())
                    .as("amountMinor=%d 必须原样写入 amount.total", minor)
                    .isEqualTo(minor);
        }
    }

    @Test
    @DisplayName("币种归一化为大写 CNY；描述/订单号/回调地址原样入报文 [FR-004]")
    void prepayBodyCarriesRequiredFields() throws Exception {
        JsonNode body = MAPPER.readTree(gateway().buildPrepayBody(nativeCommand(100L)));

        assertThat(body.get("appid").asText()).isEqualTo("wx1234567890abcdef");
        assertThat(body.get("mchid").asText()).isEqualTo("1900000109");
        assertThat(body.get("out_trade_no").asText()).isEqualTo("PM001");
        assertThat(body.get("description").asText()).isEqualTo("测试商品");
        assertThat(body.get("notify_url").asText()).isEqualTo("https://example.com/notify");
        assertThat(body.get("amount").get("currency").asText()).isEqualTo("CNY");
        // NATIVE 不带 payer / scene_info
        assertThat(body.has("payer")).isFalse();
        assertThat(body.has("scene_info")).isFalse();
    }

    // ---- FR-004：场景 → 端点 / 凭证字段 ----

    @Test
    @DisplayName("场景分派：NATIVE→native、JSAPI/MINI_PROGRAM→jsapi、H5→h5 [FR-004]")
    void sceneDispatchesToEndpoint() {
        assertThat(WechatSdkGateway.pathForScene(PaymentScene.NATIVE)).isEqualTo("/v3/pay/transactions/native");
        assertThat(WechatSdkGateway.pathForScene(PaymentScene.JSAPI)).isEqualTo("/v3/pay/transactions/jsapi");
        assertThat(WechatSdkGateway.pathForScene(PaymentScene.MINI_PROGRAM)).isEqualTo("/v3/pay/transactions/jsapi");
        assertThat(WechatSdkGateway.pathForScene(PaymentScene.H5)).isEqualTo("/v3/pay/transactions/h5");

        assertThat(WechatSdkGateway.responseKeyForScene(PaymentScene.NATIVE)).isEqualTo("code_url");
        assertThat(WechatSdkGateway.responseKeyForScene(PaymentScene.JSAPI)).isEqualTo("prepay_id");
        assertThat(WechatSdkGateway.responseKeyForScene(PaymentScene.MINI_PROGRAM)).isEqualTo("prepay_id");
        assertThat(WechatSdkGateway.responseKeyForScene(PaymentScene.H5)).isEqualTo("h5_url");
    }

    @Test
    @DisplayName("不支持的场景（WEB / APP）⇒ 明确拒绝，不静默走别的端点 [FR-003]")
    void unsupportedSceneIsRejected() {
        assertThatThrownBy(() -> WechatSdkGateway.pathForScene(PaymentScene.WEB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support scene");
        assertThatThrownBy(() -> WechatSdkGateway.pathForScene(PaymentScene.APP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JSAPI / MINI_PROGRAM 报文带 payer.openid [FR-004]")
    void jsapiBodyCarriesOpenid() throws Exception {
        WechatGateway.PrepayCommand cmd = new WechatGateway.PrepayCommand(
                PaymentScene.JSAPI, "PM002", 100L, "CNY", "商品",
                "https://example.com/notify", "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o", null, null);

        JsonNode body = MAPPER.readTree(gateway().buildPrepayBody(cmd));

        assertThat(body.get("payer").get("openid").asText()).isEqualTo("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o");
    }

    @Test
    @DisplayName("H5 报文带 scene_info（h5_info.type=Wap + 可选 payer_client_ip）[FR-004]")
    void h5BodyCarriesSceneInfo() throws Exception {
        WechatGateway.PrepayCommand cmd = new WechatGateway.PrepayCommand(
                PaymentScene.H5, "PM003", 100L, "CNY", "商品",
                "https://example.com/notify", null, "203.0.113.7", null);

        JsonNode body = MAPPER.readTree(gateway().buildPrepayBody(cmd));

        assertThat(body.get("scene_info").get("h5_info").get("type").asText()).isEqualTo("Wap");
        assertThat(body.get("scene_info").get("payer_client_ip").asText()).isEqualTo("203.0.113.7");
    }

    // ---- FR-006：退款单号映射 + 金额 ----

    @Test
    @DisplayName("退款报文：out_refund_no = 平台 refundNo；金额分直传 [FR-006][INV-4]")
    void refundBodyMapsRefundNoAndAmount() throws Exception {
        WechatGateway.RefundCommand cmd = new WechatGateway.RefundCommand(
                "PM001", "4200001234202609251234567890", "R20260925001",
                100L, 100L, "CNY", "用户申请", "https://example.com/refund-notify");

        JsonNode body = MAPPER.readTree(gateway().buildRefundBody(cmd));

        assertThat(body.get("out_refund_no").asText()).isEqualTo("R20260925001");
        assertThat(body.get("transaction_id").asText()).isEqualTo("4200001234202609251234567890");
        assertThat(body.has("out_trade_no")).isFalse();
        assertThat(body.get("amount").get("refund").asLong()).isEqualTo(100L);
        assertThat(body.get("amount").get("total").asLong()).isEqualTo(100L);
        assertThat(body.get("amount").get("currency").asText()).isEqualTo("CNY");
        assertThat(body.get("reason").asText()).isEqualTo("用户申请");
    }

    @Test
    @DisplayName("退款：无 transactionId 时回退用 out_trade_no 定位原单 [FR-006]")
    void refundFallsBackToOutTradeNo() throws Exception {
        WechatGateway.RefundCommand cmd = new WechatGateway.RefundCommand(
                "PM001", null, "R001", 100L, 100L, "CNY", null, null);

        JsonNode body = MAPPER.readTree(gateway().buildRefundBody(cmd));

        assertThat(body.get("out_trade_no").asText()).isEqualTo("PM001");
        assertThat(body.has("transaction_id")).isFalse();
        assertThat(body.has("reason")).isFalse();
        assertThat(body.has("notify_url")).isFalse();
    }

    // ---- spec §8 / INV-7：状态映射 ----

    @Test
    @DisplayName("trade_state 映射：SUCCESS⇒SUCCESS；REFUND/CLOSED/REVOKED/PAYERROR⇒FAILURE 侧 [INV-7]")
    void tradeStateMapping() {
        assertThat(WechatSdkGateway.mapTradeState("SUCCESS")).isEqualTo(WechatGateway.TradeState.SUCCESS);
        assertThat(WechatSdkGateway.mapTradeState("REFUND")).isEqualTo(WechatGateway.TradeState.REFUND);
        assertThat(WechatSdkGateway.mapTradeState("CLOSED")).isEqualTo(WechatGateway.TradeState.CLOSED);
        assertThat(WechatSdkGateway.mapTradeState("REVOKED")).isEqualTo(WechatGateway.TradeState.REVOKED);
        assertThat(WechatSdkGateway.mapTradeState("PAYERROR")).isEqualTo(WechatGateway.TradeState.PAYERROR);
    }

    @Test
    @DisplayName("USERPAYING / NOTPAY ⇒ 不判失败（保留给上层映射为 UNKNOWN）[INV-7]")
    void pendingStatesAreNotFailures() {
        assertThat(WechatSdkGateway.mapTradeState("USERPAYING")).isEqualTo(WechatGateway.TradeState.USERPAYING);
        assertThat(WechatSdkGateway.mapTradeState("NOTPAY")).isEqualTo(WechatGateway.TradeState.NOTPAY);
    }

    @Test
    @DisplayName("未知 trade_state / null ⇒ UNKNOWN，绝不猜成成功或失败 [INV-7]")
    void unknownTradeStateIsNotGuessed() {
        assertThat(WechatSdkGateway.mapTradeState("SOMETHING_NEW")).isEqualTo(WechatGateway.TradeState.UNKNOWN);
        assertThat(WechatSdkGateway.mapTradeState(null)).isEqualTo(WechatGateway.TradeState.UNKNOWN);
    }

    @Test
    @DisplayName("退款 status 映射：SUCCESS/CLOSED/PROCESSING/ABNORMAL；未知 ⇒ UNKNOWN [FR-006]")
    void refundStateMapping() {
        assertThat(WechatSdkGateway.mapRefundState("SUCCESS")).isEqualTo(WechatGateway.RefundState.SUCCESS);
        assertThat(WechatSdkGateway.mapRefundState("CLOSED")).isEqualTo(WechatGateway.RefundState.CLOSED);
        assertThat(WechatSdkGateway.mapRefundState("PROCESSING")).isEqualTo(WechatGateway.RefundState.PROCESSING);
        assertThat(WechatSdkGateway.mapRefundState("ABNORMAL")).isEqualTo(WechatGateway.RefundState.ABNORMAL);
        assertThat(WechatSdkGateway.mapRefundState("WAT")).isEqualTo(WechatGateway.RefundState.UNKNOWN);
        assertThat(WechatSdkGateway.mapRefundState(null)).isEqualTo(WechatGateway.RefundState.UNKNOWN);
    }
}
