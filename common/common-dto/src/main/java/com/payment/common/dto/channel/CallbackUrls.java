package com.payment.payment.application.channel;

/**
 * 回调地址对（平台 → 渠道，spec 030 / FR-103）。
 *
 * <p>⚠️ <b>资金事实边界（硬约束）</b>：{@code returnUrl} 是<b>买家付款完成后浏览器跳回的页面地址</b>，
 * 它<b>不承载任何资金事实</b>——买家可能根本没付款就点了返回，也可能付款后关掉页面从未跳转。
 * 因此 <b>MUST NOT</b> 依据 {@code returnUrl} 的到达来推进支付状态；
 * 支付状态的唯一权威来源是 {@code notifyUrl} 的异步通知与主动查询。</p>
 *
 * @param notifyUrl 异步通知地址（渠道服务器主动回调，<b>资金事实来源</b>）
 * @param returnUrl 同步跳回地址（<b>仅用于页面跳转，不承载资金事实</b>，可空）
 */
public record CallbackUrls(String notifyUrl, String returnUrl) {

    /** 只给异步通知地址（无跳回页）的工厂。 */
    public static CallbackUrls notifyOnly(String notifyUrl) {
        return new CallbackUrls(notifyUrl, null);
    }
}
