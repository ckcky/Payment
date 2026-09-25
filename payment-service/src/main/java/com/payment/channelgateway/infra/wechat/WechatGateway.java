package com.payment.channelgateway.infra.wechat;

import com.payment.common.dto.channel.PaymentScene;

import java.time.Instant;

/**
 * 微信支付网关端口（spec 039 / FR-001）——<b>渠道私有包内的领域侧契约</b>。
 *
 * <p><b>存在的唯一理由：把 {@code wechatpay-java} SDK 关进笼子</b>——与
 * {@code StripeGateway} / {@code AlipayGateway} 同源的设计（INV-7 / ADR-0076）。
 * SDK 是第三方依赖，其类名、异常体系、返回结构随版本变化；让它扩散到插件之外的任何一层，
 * SDK 的升级节奏就会绑架平台代码。收口后，将来换成「纯 JDK {@code HttpClient} + 自签 RSA」
 * 只需新写一个实现类——<b>零扩散</b>。</p>
 *
 * <h3>为什么本端口可以出现微信概念（{@code out_trade_no} / {@code prepay_id} / {@code trade_state}）</h3>
 * 本接口位于 {@code infra.wechat}——<b>渠道私有包</b>，不是内核。分层口径是：
 * <b>内核只认平台概念</b>（{@code ChannelResult} / {@code ParsedCallback}），
 * <b>渠道包内可以认渠道概念</b>。微信概念 → 平台概念的翻译由
 * {@code WechatChannelPlugin} 完成，翻译关系不反向渗透到 {@code application/**}。</p>
 *
 * <h3>金额口径（INV-4，硬约束）</h3>
 * 微信 V3 一律用<b>分</b>（{@code amount.total} / {@code amount.refund}，整型）；
 * 平台 {@code amountMinor} 同为分，故<b>直接透传</b>——
 * <b>禁止任何 {@code *100} / {@code /100}</b>（换算即金额放大 100 倍）。</p>
 */
public interface WechatGateway {

    /**
     * 统一下单（按 {@link PrepayCommand#scene()} 分派到 NATIVE / JSAPI / H5 端点）。
     *
     * <p>返回的 {@code prepayKey} 是<b>场景相关的凭证原文</b>：NATIVE → {@code code_url}、
     * JSAPI / MINI_PROGRAM → {@code prepay_id}、H5 → {@code h5_url}。
     * 语义同支付宝 {@code page.pay}：<b>渠道受理 ≠ 买家已付款</b>，
     * payment MUST 停在 {@code PROCESSING}（INV-6：不记账、不通知）。</p>
     */
    PrepayResult prepay(PrepayCommand command);

    /** 按商户订单号查询（{@code GET /v3/pay/transactions/out-trade-no/{no}}）。 */
    QueryResult queryByOutTradeNo(String outTradeNo);

    /** 按微信支付订单号查询（{@code GET /v3/pay/transactions/id/{id}}）。 */
    QueryResult queryByTransactionId(String transactionId);

    /** 申请退款（{@code POST /v3/refund/domestic/refunds}），退款单号用平台 {@code refundNo} 映射 {@code out_refund_no}。 */
    RefundResult refund(RefundCommand command);

    /**
     * 组装 JSAPI 调起支付参数（含 {@code paySign}）。
     *
     * <p>JSAPI / MINI_PROGRAM 场景下，{@code prepay_id} 只是中间产物——前端调起收银台需要
     * 一份<b>已签名</b>的参数包（{@code appId} / {@code timeStamp} / {@code nonceStr} /
     * {@code package} / {@code signType} / {@code paySign}）。签名用商户私钥，故归本端口。</p>
     *
     * @param prepayId 统一下单返回的 {@code prepay_id}
     * @return 可直接交给前端 SDK 的 JSON 串
     */
    String jsapiPayParams(String prepayId);

    /**
     * 验签并解密 V3 异步通知（spec 039 / FR-007）。
     *
     * <p><b>顺序不可颠倒（INV-5）</b>：① 用平台证书/公钥验签
     * （基串 {@code timestamp\nnonce\nbody\n}）→ ② 用 APIv3 密钥 AES-256-GCM 解密
     * {@code resource} → ③ 投影成平台可读字段。<b>验签失败 MUST 抛异常且不得进入解密</b>——
     * 未验签就解密等于接受伪造通知（任何人都能构造一个加密体）。</p>
     *
     * @param envelope 原始通知信封（头 + 原始字节）
     * @return 通知里的平台可读字段
     * @throws com.payment.common.core.error.BizException 验签失败 / 序列号不匹配 / 解密失败
     */
    NotificationResult verifyAndDecrypt(NotificationEnvelope envelope);

    // ===================== 命令 / 结果类型（微信语义，不含 SDK 类型） =====================

    /**
     * 统一下单命令。
     *
     * @param scene       平台支付场景（决定端点与凭证形态）
     * @param outTradeNo  商户订单号（平台 {@code paymentNo}）
     * @param amountMinor 金额（<b>分</b>，直传 {@code amount.total}）
     * @param currency    币种（微信目前仅 CNY）
     * @param description 商品描述（微信必填）
     * @param notifyUrl   回调地址（资金事实来源；微信每个下单请求都带）
     * @param openid      付款人 openid（JSAPI / MINI_PROGRAM 必填）
     * @param clientIp    客户端 IP（H5 场景风控用，可空）
     * @param expireAt    订单失效时间（可空）
     */
    record PrepayCommand(PaymentScene scene, String outTradeNo, long amountMinor, String currency,
                         String description, String notifyUrl, String openid, String clientIp,
                         Instant expireAt) {
    }

    /**
     * 统一下单结果。
     *
     * @param transportOk  通信是否成功完成（{@code false} ⇒ 可重试，绝不臆断成败）
     * @param prepayKey    场景相关凭证原文（成功时非空）
     * @param errorCode    渠道业务错误码（如 {@code PARAM_ERROR}；成功时为 {@code null}）
     * @param errorMessage 渠道错误描述
     */
    record PrepayResult(boolean transportOk, String prepayKey, String errorCode, String errorMessage) {

        public static PrepayResult ok(String prepayKey) {
            return new PrepayResult(true, prepayKey, null, null);
        }

        public static PrepayResult businessFailure(String errorCode, String errorMessage) {
            return new PrepayResult(true, null, errorCode, errorMessage);
        }

        public static PrepayResult transportFailure(String errorMessage) {
            return new PrepayResult(false, null, null, errorMessage);
        }

        /** 是否成功受理（通信成功且无业务错误码）。 */
        public boolean accepted() {
            return transportOk && errorCode == null;
        }
    }

    /** 微信 {@code trade_state}（本端口的中立投影，映射口径见 spec §8 / INV-7）。 */
    enum TradeState {
        SUCCESS, REFUND, NOTPAY, CLOSED, REVOKED, USERPAYING, PAYERROR,
        /** 无法识别 / 响应不完整——<b>无结论</b>，绝不猜成成功或失败。 */
        UNKNOWN
    }

    /**
     * 查询结果。
     *
     * @param transportOk      通信是否成功完成
     * @param tradeState       交易状态（无法识别时为 {@link TradeState#UNKNOWN}）
     * @param transactionId    微信支付订单号
     * @param outTradeNo       商户订单号
     * @param amountTotalMinor 订单总金额（<b>分</b>；用于回调金额校验）
     * @param currency         币种
     * @param errorCode        渠道业务错误码（如 {@code ORDER_NOT_EXIST}）
     * @param errorMessage     渠道错误描述
     */
    record QueryResult(boolean transportOk, TradeState tradeState, String transactionId,
                       String outTradeNo, Long amountTotalMinor, String currency,
                       String errorCode, String errorMessage) {

        public static QueryResult ok(TradeState state, String transactionId, String outTradeNo,
                                     Long amountTotalMinor, String currency) {
            return new QueryResult(true, state, transactionId, outTradeNo, amountTotalMinor, currency, null, null);
        }

        public static QueryResult businessFailure(String errorCode, String errorMessage) {
            return new QueryResult(true, TradeState.UNKNOWN, null, null, null, null, errorCode, errorMessage);
        }

        public static QueryResult transportFailure(String errorMessage) {
            return new QueryResult(false, TradeState.UNKNOWN, null, null, null, null, null, errorMessage);
        }
    }

    /** 微信退款状态（{@code status}）。 */
    enum RefundState {
        SUCCESS, CLOSED, PROCESSING, ABNORMAL,
        /** 无法识别——<b>无结论</b>。 */
        UNKNOWN
    }

    /**
     * 退款命令。
     *
     * @param outTradeNo   商户订单号（与 {@code transactionId} 二选一，优先后者）
     * @param transactionId 微信支付订单号
     * @param outRefundNo  商户退款单号（平台 {@code refundNo}）
     * @param refundMinor  退款金额（<b>分</b>）
     * @param totalMinor   原订单总金额（<b>分</b>，微信必填）
     * @param currency     币种
     * @param reason       退款原因（可空）
     * @param notifyUrl    退款结果回调地址（可空）
     */
    record RefundCommand(String outTradeNo, String transactionId, String outRefundNo,
                         long refundMinor, long totalMinor, String currency,
                         String reason, String notifyUrl) {
    }

    /**
     * 退款结果。
     *
     * @param transportOk  通信是否成功完成
     * @param refundId     微信退款单号
     * @param state        退款状态
     * @param errorCode    渠道业务错误码（如 {@code NOTENOUGH} / {@code REFUND_OVER_TIME_LIMIT}）
     * @param errorMessage 渠道错误描述
     */
    record RefundResult(boolean transportOk, String refundId, RefundState state,
                        String errorCode, String errorMessage) {

        public static RefundResult ok(String refundId, RefundState state) {
            return new RefundResult(true, refundId, state, null, null);
        }

        public static RefundResult businessFailure(String errorCode, String errorMessage) {
            return new RefundResult(true, null, RefundState.UNKNOWN, errorCode, errorMessage);
        }

        public static RefundResult transportFailure(String errorMessage) {
            return new RefundResult(false, null, RefundState.UNKNOWN, null, errorMessage);
        }
    }

    /**
     * V3 通知的原始信封（头 + 原始字节）。
     *
     * <p>{@code body} 必须是<b>原始字节解出的字符串</b>，不可重新序列化——验签基串用的是
     * 原始报文，重新序列化会改变基串导致必然验签失败（同 Stripe 的原始字节纪律）。</p>
     *
     * @param timestamp {@code Wechatpay-Timestamp}
     * @param nonce     {@code Wechatpay-Nonce}
     * @param signature {@code Wechatpay-Signature}（Base64）
     * @param serial    {@code Wechatpay-Serial}（平台证书序列号 / 平台公钥 ID）
     * @param body      原始报文体（JSON）
     */
    record NotificationEnvelope(String timestamp, String nonce, String signature, String serial, String body) {
    }

    /**
     * 通知里的平台可读字段（已验签 + 已解密）。
     *
     * @param outTradeNo       商户订单号（平台 {@code paymentNo}）
     * @param transactionId    微信支付订单号
     * @param tradeState       交易状态原文（映射口径见 spec §8）
     * @param amountTotalMinor 订单总金额（<b>分</b>；通知未带时为 {@code null}）
     * @param currency         币种（通知未带时为 {@code null}）
     */
    record NotificationResult(String outTradeNo, String transactionId, String tradeState,
                              Long amountTotalMinor, String currency) {
    }
}
