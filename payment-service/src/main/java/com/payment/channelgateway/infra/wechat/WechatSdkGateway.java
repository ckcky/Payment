package com.payment.channelgateway.infra.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.channel.PaymentScene;
import com.wechat.pay.java.core.util.PemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 微信支付网关的 SDK / HTTP 实现（spec 039 / FR-004~FR-006）——
 * <b>全仓唯一允许 import {@code com.wechat.pay.*} 的类</b>。
 *
 * <p>与 {@code StripeSdkGateway} / {@code AlipaySdkGateway} 同源的收口约定（INV-7 / ADR-0076）：
 * 本类是 {@code wechatpay-java} 的<b>唯一出口</b>。插件、内核、应用层一律只见
 * {@link WechatGateway} 的渠道概念，不见 SDK 类型。</p>
 *
 * <h3>签名为什么不直接用 SDK 的 {@code WechatPay2Credential}</h3>
 * 见 {@link WechatPaySigner} 的类注释：SDK 的 {@code timestamp} / {@code nonce_str} 不可注入，
 * FR-011 的「逐字节签名金标准」无法成立。故本类用 {@link WechatPaySigner} 自建
 * {@code Authorization} 头，SDK 只承担 RSA 运算（{@code RSASigner}）与 PEM 解析（{@code PemUtil}）。</p>
 *
 * <h3>异常归一化（INV-3 同口径）</h3>
 * <ul>
 *   <li>连接失败 / 超时 / 中断 / 非 2xx/4xx ⇒ {@code transportOk=false}（可重试）——
 *       <b>绝不臆断成败</b>；</li>
 *   <li>4xx 带业务错误码（{@code code} / {@code message}）⇒ {@code transportOk=true} +
 *       {@code errorCode}，由插件翻成 {@code businessFailure}；</li>
 *   <li>微信错误码原文<b>MUST NOT</b> 出现在平台语义里——那等于把微信协议泄漏进平台。</li>
 * </ul>
 *
 * <h3>{@code enabled=false} 时完全惰性</h3>
 * 未启用时不加载任何私钥（连文件都不读），{@link #signer} 为 {@code null}；
 * 此时内核会在模态门控阶段以 400 拒绝 SANDBOX 请求（FR-241），本类的方法不会被触达。</p>
 */
public class WechatSdkGateway implements WechatGateway {

    private static final Logger log = LoggerFactory.getLogger(WechatSdkGateway.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USER_AGENT = "payment-arch/1.0 (wechat-pay-channel-plugin)";

    /** 微信 V3 时间字段格式（RFC3339，带时区偏移）。 */
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    // ---- V3 端点（path，不含 host；GET 的 query 参与签名） ----

    static final String PATH_NATIVE = "/v3/pay/transactions/native";
    static final String PATH_JSAPI = "/v3/pay/transactions/jsapi";
    static final String PATH_H5 = "/v3/pay/transactions/h5";
    static final String PATH_REFUND = "/v3/refund/domestic/refunds";

    private final WechatPayProperties properties;
    private final String apiBaseUrl;
    private final HttpClient httpClient;

    /** 未启用（{@code enabled=false}）时为 {@code null}——不加载私钥。 */
    private final WechatPaySigner signer;

    /** 平台验签公钥（{@code enabled=false} 时为 {@code null}——不加载任何凭据）。 */
    private final PublicKey platformPublicKey;

    /** 平台公钥 ID / 证书序列号（用于与 {@code Wechatpay-Serial} 比对）。 */
    private final String platformKeyId;

    /** APIv3 密钥字节（通知 {@code resource} 的 AES-256-GCM 解密用）。 */
    private final byte[] apiV3KeyBytes;

    public WechatSdkGateway(WechatPayProperties properties) {
        this.properties = properties;
        this.apiBaseUrl = trimTrailingSlash(properties.getApiBaseUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        // enabled=false ⇒ 不读任何凭据（连文件都不读）；SANDBOX 请求会被内核 400 挡在前面
        if (properties.hasPrivateKey()) {
            this.signer = buildSigner(properties);
        } else {
            this.signer = null;
        }
        if (properties.hasPlatformKey()) {
            LoadedPlatformKey loaded = loadPlatformKey(properties);
            this.platformPublicKey = loaded.publicKey();
            this.platformKeyId = loaded.keyId();
        } else {
            this.platformPublicKey = null;
            this.platformKeyId = null;
        }
        this.apiV3KeyBytes = properties.getApiV3Key() == null || properties.getApiV3Key().isBlank()
                ? null
                : properties.getApiV3Key().getBytes(StandardCharsets.UTF_8);
    }

    private static WechatPaySigner buildSigner(WechatPayProperties p) {
        PrivateKey key = p.getPrivateKey() != null && !p.getPrivateKey().isBlank()
                ? PemUtil.loadPrivateKeyFromString(p.getPrivateKey())
                : PemUtil.loadPrivateKeyFromPath(p.getPrivateKeyPath());
        return new WechatPaySigner(p.getMchId(), p.getMerchantSerialNo(), key);
    }

    /**
     * 加载平台验签材料（平台公钥 或 平台证书，二选一）。
     *
     * <p>平台证书的「密钥 ID」取证书序列号（微信规范）；平台公钥的密钥 ID 必须显式配置
     * （{@code platformPublicKeyId}），否则无法与 {@code Wechatpay-Serial} 比对。</p>
     */
    private static LoadedPlatformKey loadPlatformKey(WechatPayProperties p) {
        if (p.getPlatformPublicKey() != null && !p.getPlatformPublicKey().isBlank()) {
            return new LoadedPlatformKey(PemUtil.loadPublicKeyFromString(p.getPlatformPublicKey()),
                    p.getPlatformPublicKeyId());
        }
        X509Certificate cert = PemUtil.loadX509FromPath(p.getPlatformCertPath());
        return new LoadedPlatformKey(cert.getPublicKey(), PemUtil.getSerialNumber(cert));
    }

    private record LoadedPlatformKey(PublicKey publicKey, String keyId) {
    }

    // ===================== 统一下单（FR-004） =====================

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        String path = pathForScene(command.scene());
        String body = buildPrepayBody(command);
        try {
            HttpResponse<String> response = execute("POST", path, body);
            if (!isSuccess(response.statusCode())) {
                return classifyFailure(response);
            }
            JsonNode node = readJson(response.body());
            String key = text(node, responseKeyForScene(command.scene()));
            if (key == null || key.isBlank()) {
                // 200 却拿不到凭证：属于渠道侧异常，按「无结论」处理，不臆断成败
                return PrepayResult.transportFailure(
                        "wechat returned no " + responseKeyForScene(command.scene()));
            }
            return PrepayResult.ok(key);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("wechat prepay transport failure scene={} outTradeNo={} ex={}",
                    command.scene(), command.outTradeNo(), e.toString());
            return PrepayResult.transportFailure("wechat prepay failed: " + e.getClass().getSimpleName());
        }
    }

    /** 场景 → 端点（微信 MINI_PROGRAM 复用 JSAPI 端点，仅 {@code payer.openid} 语义不同）。 */
    static String pathForScene(PaymentScene scene) {
        return switch (scene) {
            case NATIVE -> PATH_NATIVE;
            case JSAPI, MINI_PROGRAM -> PATH_JSAPI;
            case H5 -> PATH_H5;
            default -> throw new IllegalArgumentException(
                    "wechat plugin does not support scene " + scene
                            + "; supported: NATIVE / JSAPI / MINI_PROGRAM / H5");
        };
    }

    /** 场景 → 响应里承载凭证的字段名。 */
    static String responseKeyForScene(PaymentScene scene) {
        return switch (scene) {
            case NATIVE -> "code_url";
            case JSAPI, MINI_PROGRAM -> "prepay_id";
            case H5 -> "h5_url";
            default -> throw new IllegalArgumentException("unsupported scene for wechat: " + scene);
        };
    }

    /**
     * 构造统一下单报文（包内可见，供 INV-4 金额单测直接断言，无需起 HTTP）。
     *
     * <p><b>INV-4</b>：{@code amount.total} 直接写 {@code command.amountMinor()}——
     * 微信 V3 与平台同为「分」，<b>禁止任何 {@code *100} / {@code /100}</b>。</p>
     */
    String buildPrepayBody(PrepayCommand command) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("appid", properties.getAppId());
        root.put("mchid", properties.getMchId());
        root.put("description", command.description());
        root.put("out_trade_no", command.outTradeNo());
        root.put("notify_url", command.notifyUrl());
        if (command.expireAt() != null) {
            root.put("time_expire", formatRfc3339(command.expireAt()));
        }
        ObjectNode amount = root.putObject("amount");
        amount.put("total", command.amountMinor());
        amount.put("currency", normalizeCurrency(command.currency()));
        if (command.scene() == PaymentScene.JSAPI || command.scene() == PaymentScene.MINI_PROGRAM) {
            root.putObject("payer").put("openid", command.openid());
        }
        if (command.scene() == PaymentScene.H5) {
            ObjectNode sceneInfo = root.putObject("scene_info");
            if (command.clientIp() != null && !command.clientIp().isBlank()) {
                sceneInfo.put("payer_client_ip", command.clientIp());
            }
            sceneInfo.putObject("h5_info").put("type", "Wap");
        }
        return root.toString();
    }

    // ===================== 查询（FR-005） =====================

    @Override
    public QueryResult queryByOutTradeNo(String outTradeNo) {
        return query("/v3/pay/transactions/out-trade-no/" + encode(outTradeNo));
    }

    @Override
    public QueryResult queryByTransactionId(String transactionId) {
        return query("/v3/pay/transactions/id/" + encode(transactionId));
    }

    private QueryResult query(String pathWithoutMchid) {
        String path = pathWithoutMchid + "?mchid=" + encode(properties.getMchId());
        try {
            HttpResponse<String> response = execute("GET", path, null);
            if (!isSuccess(response.statusCode())) {
                if (isClientError(response.statusCode())) {
                    JsonNode err = readJson(response.body());
                    return QueryResult.businessFailure(text(err, "code"), text(err, "message"));
                }
                return QueryResult.transportFailure("wechat query http " + response.statusCode());
            }
            JsonNode node = readJson(response.body());
            Long total = node.hasNonNull("amount") && node.get("amount").hasNonNull("total")
                    ? node.get("amount").get("total").asLong() : null;
            String currency = node.hasNonNull("amount") && node.get("amount").hasNonNull("currency")
                    ? node.get("amount").get("currency").asText() : null;
            return QueryResult.ok(mapTradeState(text(node, "trade_state")),
                    text(node, "transaction_id"), text(node, "out_trade_no"), total, currency);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("wechat query transport failure path={} ex={}", path, e.toString());
            return QueryResult.transportFailure("wechat query failed: " + e.getClass().getSimpleName());
        }
    }

    /** 微信 {@code trade_state} → 中立投影（映射口径见 spec §8 / INV-7）。 */
    static TradeState mapTradeState(String raw) {
        if (raw == null) {
            return TradeState.UNKNOWN;
        }
        return switch (raw) {
            case "SUCCESS" -> TradeState.SUCCESS;
            case "REFUND" -> TradeState.REFUND;
            case "NOTPAY" -> TradeState.NOTPAY;
            case "CLOSED" -> TradeState.CLOSED;
            case "REVOKED" -> TradeState.REVOKED;
            case "USERPAYING" -> TradeState.USERPAYING;
            case "PAYERROR" -> TradeState.PAYERROR;
            default -> TradeState.UNKNOWN;
        };
    }

    // ===================== 退款（FR-006） =====================

    @Override
    public RefundResult refund(RefundCommand command) {
        String body = buildRefundBody(command);
        try {
            HttpResponse<String> response = execute("POST", PATH_REFUND, body);
            if (!isSuccess(response.statusCode())) {
                if (isClientError(response.statusCode())) {
                    JsonNode err = readJson(response.body());
                    return RefundResult.businessFailure(text(err, "code"), text(err, "message"));
                }
                return RefundResult.transportFailure("wechat refund http " + response.statusCode());
            }
            JsonNode node = readJson(response.body());
            return RefundResult.ok(text(node, "refund_id"), mapRefundState(text(node, "status")));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("wechat refund transport failure outRefundNo={} ex={}",
                    command.outRefundNo(), e.toString());
            return RefundResult.transportFailure("wechat refund failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 构造退款报文（包内可见，供单测断言 {@code out_refund_no} 与金额单位）。
     *
     * <p>{@code out_refund_no} 用平台 {@code refundNo} 直接映射（FR-006），
     * 使退款单号在平台与渠道间可逐字对齐——排障时不必再查映射表。</p>
     */
    String buildRefundBody(RefundCommand command) {
        ObjectNode root = MAPPER.createObjectNode();
        if (command.transactionId() != null && !command.transactionId().isBlank()) {
            root.put("transaction_id", command.transactionId());
        } else {
            root.put("out_trade_no", command.outTradeNo());
        }
        root.put("out_refund_no", command.outRefundNo());
        if (command.reason() != null && !command.reason().isBlank()) {
            root.put("reason", command.reason());
        }
        if (command.notifyUrl() != null && !command.notifyUrl().isBlank()) {
            root.put("notify_url", command.notifyUrl());
        }
        ObjectNode amount = root.putObject("amount");
        amount.put("refund", command.refundMinor());
        amount.put("total", command.totalMinor());
        amount.put("currency", normalizeCurrency(command.currency()));
        return root.toString();
    }

    /** 微信退款 {@code status} → 中立投影。 */
    static RefundState mapRefundState(String raw) {
        if (raw == null) {
            return RefundState.UNKNOWN;
        }
        return switch (raw) {
            case "SUCCESS" -> RefundState.SUCCESS;
            case "CLOSED" -> RefundState.CLOSED;
            case "PROCESSING" -> RefundState.PROCESSING;
            case "ABNORMAL" -> RefundState.ABNORMAL;
            default -> RefundState.UNKNOWN;
        };
    }

    // ===================== JSAPI 调起参数（FR-004） =====================

    @Override
    public String jsapiPayParams(String prepayId) {
        if (signer == null) {
            // 不可达：SANDBOX 请求在插件模态门控阶段已被 400 拒绝（enabled=false）
            throw new IllegalStateException("wechat real mode is not enabled; refusing to sign jsapi params");
        }
        String timeStamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String packageValue = "prepay_id=" + prepayId;
        String paySign = signer.signMessage(WechatPaySigner.jsapiBaseString(
                properties.getAppId(), timeStamp, nonceStr, packageValue));

        ObjectNode node = MAPPER.createObjectNode();
        node.put("appId", properties.getAppId());
        node.put("timeStamp", timeStamp);
        node.put("nonceStr", nonceStr);
        node.put("package", packageValue);
        node.put("signType", "RSA");
        node.put("paySign", paySign);
        return node.toString();
    }

    // ===================== 通知验签 + 解密（FR-007 / INV-5） =====================

    /**
     * {@inheritDoc}
     *
     * <p><b>顺序硬约束（INV-5）</b>：先验签、后解密。任一步失败都抛
     * {@link BizException}（由通用回调端点转 403），<b>不触达</b>任何状态推进（INV-10）。</p>
     */
    @Override
    public NotificationResult verifyAndDecrypt(NotificationEnvelope envelope) {
        // ① 验签：未通过则绝不进入解密
        verifySignature(envelope);
        // ② 解密 resource
        JsonNode plain = decryptResource(envelope.body());
        // ③ 投影为平台可读字段
        Long total = plain.hasNonNull("amount") && plain.get("amount").hasNonNull("total")
                ? plain.get("amount").get("total").asLong() : null;
        String currency = plain.hasNonNull("amount") && plain.get("amount").hasNonNull("currency")
                ? plain.get("amount").get("currency").asText() : null;
        return new NotificationResult(text(plain, "out_trade_no"), text(plain, "transaction_id"),
                text(plain, "trade_state"), total, currency);
    }

    /** 平台证书/公钥验签：基串 = {@code timestamp\nnonce\nbody\n}（与请求签名不同的第二套基串）。 */
    private void verifySignature(NotificationEnvelope envelope) {
        if (platformPublicKey == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat platform key is not configured; cannot verify notification");
        }
        if (platformKeyId != null && !platformKeyId.isBlank()
                && !platformKeyId.equalsIgnoreCase(envelope.serial())) {
            // F5：Wechatpay-Serial 对不上 ⇒ 拒绝（可能是伪造，也可能是平台证书轮换未同步）
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat notification serial mismatch: expected=" + platformKeyId
                            + " actual=" + envelope.serial());
        }
        String message = envelope.timestamp() + "\n" + envelope.nonce() + "\n" + envelope.body() + "\n";
        boolean valid;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(platformPublicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            valid = verifier.verify(Base64.getDecoder().decode(envelope.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("wechat notification signature malformed: {}", e.toString());
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "wechat notification signature malformed");
        }
        if (!valid) {
            log.warn("wechat notification signature invalid");
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "wechat notification signature invalid");
        }
    }

    /** AES-256-GCM 解密 {@code resource}（AAD = {@code associated_data}，IV = {@code nonce}）。 */
    private JsonNode decryptResource(String body) {
        if (apiV3KeyBytes == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat apiV3Key is not configured; cannot decrypt notification");
        }
        JsonNode resource = readJson(body).get("resource");
        if (resource == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "wechat notification carries no resource");
        }
        String algorithm = text(resource, "algorithm");
        String ciphertext = text(resource, "ciphertext");
        String nonce = text(resource, "nonce");
        String associatedData = text(resource, "associated_data");
        if (!"AEAD_AES_256_GCM".equals(algorithm)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "unsupported wechat notification algorithm: " + algorithm);
        }
        if (ciphertext == null || nonce == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "wechat notification resource missing ciphertext/nonce");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(apiV3KeyBytes, "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return MAPPER.readTree(new String(plain, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // 解密失败 = 密钥不对或报文被篡改：与验签失败同后果（拒绝），故合并处理
            log.warn("wechat notification decrypt failed: {}", e.toString());
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "wechat notification decrypt failed");
        }
    }

    // ===================== HTTP 执行 =====================

    private HttpResponse<String> execute(String method, String pathWithQuery, String body)
            throws IOException, InterruptedException {
        if (signer == null) {
            // 不可达：SANDBOX 请求在插件模态门控阶段已被 400 拒绝（enabled=false）
            throw new IllegalStateException("wechat real mode is not enabled; refusing to sign request");
        }
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String authorization = signer.authorization(method, pathWithQuery, body, timestamp, nonce);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + pathWithQuery))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private PrepayResult classifyFailure(HttpResponse<String> response) {
        if (isClientError(response.statusCode())) {
            JsonNode err = readJson(response.body());
            return PrepayResult.businessFailure(text(err, "code"), text(err, "message"));
        }
        return PrepayResult.transportFailure("wechat prepay http " + response.statusCode());
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }

    private static JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            // 非 JSON 响应（网关错误页等）：当成「无字段」处理，由调用方按缺失字段走失败分支
            log.warn("wechat response is not json: {}", abbreviate(body));
            return MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static String formatRfc3339(Instant instant) {
        return RFC3339.format(instant.atZone(ZoneId.systemDefault()));
    }

    private static String encode(String segment) {
        return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.mch.weixin.qq.com";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
