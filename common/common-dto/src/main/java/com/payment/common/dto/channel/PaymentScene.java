package com.payment.common.dto.channel;

/**
 * 支付场景（平台内部统一枚举，spec 030 / FR-101）。
 *
 * <p>真实渠道各有自己的场景参数名与取值，本枚举是<b>平台侧的中立抽象</b>：
 * 各 Adapter 负责把本枚举映射为渠道原生参数，映射关系不得反向渗透到 {@code application/**}。</p>
 *
 * <h3>四家渠道映射对照（Javadoc 即契约，改映射必须同步改此表）</h3>
 * <table border="1">
 *   <caption>PaymentScene → 渠道原生参数</caption>
 *   <tr><th>本枚举</th><th>支付宝</th><th>微信支付</th><th>抖音支付</th><th>Stripe</th></tr>
 *   <tr><td>{@link #WEB}</td><td>{@code alipay.trade.page.pay}（PC 网站支付）</td>
 *       <td>{@code trade_type=NATIVE}</td><td>PC 端 H5</td>
 *       <td>{@code payment_method_types=card}</td></tr>
 *   <tr><td>{@link #H5}</td><td>{@code alipay.trade.wap.pay}（手机网站支付）</td>
 *       <td>{@code trade_type=MWEB}</td><td>移动端 H5</td>
 *       <td>{@code payment_method_types=card}</td></tr>
 *   <tr><td>{@link #NATIVE}</td><td>{@code alipay.trade.precreate}（扫码）</td>
 *       <td>{@code trade_type=NATIVE}（二维码）</td><td>扫码</td>
 *       <td>—</td></tr>
 *   <tr><td>{@link #JSAPI}</td><td>{@code alipay.trade.create}（需 buyer_id）</td>
 *       <td>{@code trade_type=JSAPI}（需 openid）</td><td>小程序</td>
 *       <td>—</td></tr>
 *   <tr><td>{@link #MINI_PROGRAM}</td><td>小程序支付</td><td>{@code trade_type=JSAPI}</td>
 *       <td>小程序支付</td><td>—</td></tr>
 *   <tr><td>{@link #APP}</td><td>{@code alipay.trade.app.pay}</td>
 *       <td>{@code trade_type=APP}</td><td>App 支付</td><td>—</td></tr>
 * </table>
 *
 * <p><b>显式不做</b>：不为 {@code null} 场景推导默认值（零回归，见 tasks Q7）——
 * 未传场景即不传，渠道按其自身默认处理。</p>
 */
public enum PaymentScene {

    /** PC 网站支付（浏览器跳转 / 表单提交）。 */
    WEB,
    /** 手机网站支付（WAP）。 */
    H5,
    /** 原生扫码支付（渠道返回二维码串）。 */
    NATIVE,
    /** 公众号 / 生活号内支付（需付款人 openid 或 buyer_id）。 */
    JSAPI,
    /** 小程序内支付。 */
    MINI_PROGRAM,
    /** App 内支付（渠道返回唤起参数）。 */
    APP
}
