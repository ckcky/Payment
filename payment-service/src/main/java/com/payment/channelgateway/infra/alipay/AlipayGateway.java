package com.payment.channelgateway.infra.alipay;

import java.time.Instant;
import java.util.Map;

/**
 * 支付宝网关端口（spec 030 / FR-132 / INV-7）。
 *
 * <p><b>存在的唯一理由：把 SDK 关进笼子。</b>官方 SDK（Maven 坐标 {@code com.alipay.sdk:alipay-sdk-java}）
 * 体积大、传递依赖多、带已知 CVE（FR-295 / ADR-0076，风险已由 H3 显式接受）。收口手段不是「小心使用」，而是
 * <b>架构约束</b>：{@code application/**} 与 {@code domain/**} 只认本端口，
 * SDK 的 Java 包 {@code com.alipay.api}（注意：非 Maven groupId {@code com.alipay.sdk}）
 * 只允许出现在 {@code infra/channel/alipay/AlipaySdkGateway}
 * 一个类里（ArchUnit 构建期断言，SC-A-09）。将来把 SDK 换成纯 JDK 实现
 * （{@code HttpClient} + {@code SHA256withRSA}），只需新写一个实现类——<b>零扩散</b>。</p>
 *
 * <h3>为什么端口方法签名里没有 SDK 类型</h3>
 * 端口若暴露 {@code AlipayClient} / {@code AlipayTradePagePayResponse} 之类，收口就成了空话
 * ——应用层被迫 import SDK 类型才能调用。故本端口的方法参数与返回值
 * <b>全用平台自有类型</b>（{@link Map} / {@code String} / {@link Instant}），
 * 渠道私有概念由实现类在内部翻译（FR-141）。</p>
 *
 * <h3>返回值的错误语义（FR-141）</h3>
 * 实现类负责把 SDK 异常与渠道错误码<b>归一化</b>为平台语义：
 * <ul>
 *   <li>通信失败（超时、断连、DNS 失败）⇒ {@code TransportFailure}；</li>
 *   <li>渠道明确拒绝 ⇒ {@code BusinessRejection} 携带**已映射**的
 *       {@link com.payment.common.core.rpc.BusinessCode}，<b>不带</b>渠道原始错误码；</li>
 *   <li>渠道无明确结论 ⇒ {@code Inconclusive}。</li>
 * </ul>
 * 渠道私有错误码<b>MUST NOT</b> 出现在返回值里——那等于把渠道协议泄漏进平台语义。
 */
public interface AlipayGateway {

    /**
     * 电脑网站支付（{@code alipay.trade.page.pay}）：返回<b>已签名的「自动提交表单」HTML</b>。
     *
     * <p><b>这不是一个 URL</b>（2026-09-20 修正）：官方 SDK 的
     * {@code client.pageExecute(request).getBody()} 返回的是一段
     * {@code <form …>} + {@code document.forms[0].submit()} 的 HTML 片段，
     * 由<b>浏览器渲染后自动 POST</b> 到网关换回收银台页。把它当成地址交给
     * {@code window.open(...)} / {@code <a href>}，浏览器会按「相对地址」解析这段 HTML——
     * <b>结果是一个空白页</b>（spec 030 联调时真实踩过）。
     * 调用方 MUST 按形态分流：以 {@code '<'} 开头即为表单 HTML，需包装成可加载的页面
     * （如前端用 Blob URL）再打开，详见 {@code demo.html#isFormHtmlCredential}。</p>
     *
     * <p>沙箱下这一步<b>不会</b>让买家付钱，只是拿到「去哪儿付款」的凭证（FR-136）。
     * 故调用方 MUST 把结果当 {@code accepted} 处理、payment 停 {@code PROCESSING}
     * （INV-6：钱还没到，不得记账、不得通知）。</p>
     *
     * @param outTradeNo  平台支付单号（{@code paymentNo}，PM+雪花）
     * @param amountMinor 金额（最小货币单位，<b>分</b>）
     * @param currency    币种
     * @param subject     商品标题（展示给买家）
     * @param notifyUrl   异步通知地址（<b>资金事实的唯一可信来源</b>）
     * @param returnUrl   同步跳转地址（<b>仅用于买家体验，MUST NOT 据其推进支付状态</b>）
     * @param expireAt    凭证有效期（过期链接应失效）
     * @return 页面跳转结果
     */
    PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                          String subject, String notifyUrl, String returnUrl, Instant expireAt);

    /**
     * 交易查询（{@code alipay.trade.query}）：按平台单号或渠道交易号定位原交易。
     *
     * @param outTradeNo          平台支付单号（与 {@code tradeNo} 至少给一个）
     * @param channelTransactionId 渠道交易号（即 {@code payment_attempts.channel_reference}）
     * @return 查询结果
     */
    QueryResult query(String outTradeNo, String channelTransactionId);

    /**
     * 退款（{@code alipay.trade.refund}）：沙箱下<b>同步返回</b>结论（FR-138）。
     *
     * @param outTradeNo          平台支付单号
     * @param channelTransactionId 渠道交易号
     * @param refundNo            平台退款单号（幂等键）
     * @param amountMinor         退款金额（最小货币单位，<b>分</b>）
     * @param currency            币种
     * @param reason              退款原因（进渠道对账单，非必填）
     * @return 退款结果
     */
    RefundResult refund(String outTradeNo, String channelTransactionId,
                        String refundNo, long amountMinor, String currency, String reason);

    /**
     * 异步通知验签（{@code alipay.trade.page.pay} 的 notify）。
     *
     * <p><b>这是回调三段式校验的第①段</b>（FR-202）：验签失败 ⇒ 403 且
     * <b>不触达</b>任何状态推进逻辑（INV-10）。参数表 MUST 是<b>原始全量</b>参数
     * （剔除 {@code sign} / {@code sign_type} 后参与验签），
     * 只挑几个字段传进来会导致验签必然失败。</p>
     *
     * @param rawParams 通知报文的全部参数（键值均为字符串）
     * @return 验签是否通过
     */
    boolean verifyNotify(Map<String, String> rawParams);

    // ---- 结果类型（平台自有语义，不含任何 SDK 类型） ----

    /**
     * 页面支付结果：成功拿到付款凭证（自动提交表单 HTML），或失败。
     *
     * @param redirectUrl 付款凭证原文——**自动提交表单 HTML**（不是 URL；失败时为 {@code null}）；
     *                    字段名为历史沿用，语义见 {@link #pagePay} 的说明
     * @param transportOk 通信是否成功完成
     * @param reason      失败说明（<b>平台语义</b>，不含渠道错误码原文）
     */
    record PagePayResult(String redirectUrl, boolean transportOk, String reason) {

        public static PagePayResult ok(String redirectUrl) {
            return new PagePayResult(redirectUrl, true, null);
        }

        public static PagePayResult transportFailure(String reason) {
            return new PagePayResult(null, false, reason);
        }
    }

    /**
     * 查询结果。
     *
     * @param tradeStatus 渠道交易状态原文的**平台归一化**取值：{@code SUCCESS} /
     *                    {@code CLOSED} / {@code WAIT_BUYER_PAY} / {@code UNKNOWN}
     * @param channelTransactionId 渠道交易号（有则回带，用于回填）
     * @param totalAmountMinor 渠道侧金额（最小单位；渠道未返回则 {@code null}）
     * @param transportOk 通信是否成功完成
     * @param reason 说明（平台语义）
     */
    record QueryResult(
            AlipayTradeStatus tradeStatus,
            String channelTransactionId,
            Long totalAmountMinor,
            String currency,
            boolean transportOk,
            String reason) {

        /** 渠道无明确结论（仍待买家付款、或响应不完整）——<b>不臆断成败</b>。 */
        public static QueryResult unknown(String reason) {
            return new QueryResult(AlipayTradeStatus.UNKNOWN, null, null, null, true, reason);
        }

        public static QueryResult transportFailure(String reason) {
            return new QueryResult(AlipayTradeStatus.UNKNOWN, null, null, null, false, reason);
        }
    }

    /**
     * 退款结果（沙箱下同步返回）。
     *
     * @param succeeded 渠道是否确认退款成功
     * @param channelRefundNo 渠道退款流水号
     * @param transportOk 通信是否成功完成
     * @param reason 说明（平台语义）
     */
    record RefundResult(boolean succeeded, String channelRefundNo, boolean transportOk, String reason) {

        public static RefundResult ok(String channelRefundNo) {
            return new RefundResult(true, channelRefundNo, true, null);
        }

        public static RefundResult rejected(String reason) {
            return new RefundResult(false, null, true, reason);
        }

        public static RefundResult transportFailure(String reason) {
            return new RefundResult(false, null, false, reason);
        }
    }

    /**
     * 渠道交易状态的<b>平台归一化</b>取值（spec 030 / FR-137 映射结果）。
     *
     * <p><b>刻意不放在 common-core</b>：这是支付宝协议概念的平台投影，只有本适配器一族
     * 用得到；放 common 会让「渠道协议细节」变成全局共享词汇。将来接第二家真实渠道时，
     * 若发现语义确实通用再上提——现在上提是过早抽象。</p>
     *
     * <p>取值与 {@link com.payment.common.core.rpc.BusinessCode} 的分工：
     * 本枚举描述<b>渠道那边这笔交易处于什么状态</b>；
     * {@code BusinessCode} 描述<b>平台该怎么理解这次交互</b>。映射在 Adapter 内完成（FR-141）。</p>
     */
    enum AlipayTradeStatus {
        /** 交易成功（{@code TRADE_SUCCESS} / {@code TRADE_FINISHED}）——权威成功。 */
        SUCCESS,
        /** 交易已关闭（{@code TRADE_CLOSED}）——明确失败。 */
        CLOSED,
        /** 等待买家付款（{@code WAIT_BUYER_PAY}）——<b>无结论，不臆断</b>。 */
        WAIT_BUYER_PAY,
        /** 无法识别或不完整响应——<b>无结论，不臆断</b>。 */
        UNKNOWN
    }
}
