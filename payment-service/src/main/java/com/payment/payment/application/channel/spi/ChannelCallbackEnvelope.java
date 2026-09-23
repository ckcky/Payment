package com.payment.payment.application.channel.spi;

import java.util.Map;

/**
 * 渠道异步通知的<b>原始信封</b>（渠道插件化内核 / SPI-02）。
 *
 * <p>内核从 HTTP 层把通知<b>原样搬运</b>到这里，<b>不做任何渠道语义解析</b>：
 * 不读 {@code out_trade_no}、不读 {@code data.object.id}、不猜报文格式。
 * 解析是插件的职责（{@link ChannelPlugin#parseCallback}）——这一步的归属决定了
 * 「内核是否认识渠道」：内核一旦认识某个字段名，第二家渠道就得改内核。</p>
 *
 * <h3>为什么两种载荷都保留</h3>
 * 各渠道的通知形态不一致：支付宝是 {@code application/x-www-form-urlencoded} 的
 * 参数表（且验签<b>必须</b>用原始全量参数，少一个字段就验不过）；Stripe 是
 * {@code application/json} 的事件体（且验签<b>必须</b>用原始字节，重新序列化会
 * 改变签名基串）。<b>两种都原样带给插件</b>，由插件按自己的协议挑一种用。</p>
 *
 * @param headers    请求头（小写键；验签用，如 {@code stripe-signature}）
 * @param formParams 表单参数（{@code application/x-www-form-urlencoded} 形态；非该形态为空 Map）
 * @param rawBody    原始报文体（JSON 形态验签<b>必须</b>用它，不可重新序列化）
 */
public record ChannelCallbackEnvelope(Map<String, String> headers,
                                      Map<String, String> formParams,
                                      String rawBody) {

    public ChannelCallbackEnvelope {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        formParams = formParams == null ? Map.of() : Map.copyOf(formParams);
    }

    public static ChannelCallbackEnvelope json(Map<String, String> headers, String rawBody) {
        return new ChannelCallbackEnvelope(headers, Map.of(), rawBody);
    }

    public static ChannelCallbackEnvelope form(Map<String, String> headers, Map<String, String> formParams) {
        return new ChannelCallbackEnvelope(headers, formParams, "");
    }
}
