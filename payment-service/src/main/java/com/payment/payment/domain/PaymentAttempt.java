package com.payment.payment.domain;

import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 支付尝试：记录一次渠道交互（渠道身份、渠道引用、请求/响应时间、结果与状态）。
 *
 * <p>每次尝试独立可追踪；重复回调映射到同一渠道引用；未知尝试在获得权威结果前保持未收敛。
 * 与 {@link Payment} 一致，终态（SUCCEEDED/FAILED）吸收迟到的冲突结果（返回 {@code false}），
 * 以支持乱序/重复回调的幂等处理。</p>
 */
public class PaymentAttempt {

    /** 尝试类型（Feature 016 / FR-017）：支付尝试；退款尝试（复用本表，channel_reference=渠道退款流水号）。 */
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_REFUND = "REFUND";

    /**
     * {@code extra} 中承载渠道模态的键（spec 030 / FR-151）。
     *
     * <p>落库列 {@code payment_attempts.extra_json}（TEXT 存 JSON）。写入侧与读取侧
     * <b>MUST 共用本常量</b>，避免两侧拼写漂移导致「写进去读不出来」。</p>
     */
    public static final String CHANNEL_MODE_KEY = "channelMode";

    private Long id;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String paymentNo;
    private final String channelCode;
    /** 尝试类型：PAYMENT（默认）/ REFUND（退款渠道尝试，Feature 016）。 */
    private String attemptType = TYPE_PAYMENT;
    private Instant requestedAt;
    private Instant respondedAt;
    private String channelReference;
    private PaymentAttemptStatus status = PaymentAttemptStatus.PENDING;
    private String failureReason;
    private int retryCount;
    /**
     * 最后一次失败的错误分类（由双响应码派生，供观测排障；<b>不参与重试判定</b>，ADR-0012）。
     * 重试判定只看通信响应码 {@code TransportCode}。
     */
    private PaymentAttemptErrorType errorType;
    /**
     * 本次渠道交互的资金口径（spec 018 / US1 / D2）：金额（最小货币单位）与币种。
     * PAYMENT 尝试记支付单金额；REFUND 尝试记所属支付单金额（非退款金额）。
     */
    private long amountMinor;
    private String currencyCode;
    /**
     * 渠道扩展属性（spec 030 / FR-302，落库列 {@code extra_json}）。
     *
     * <p><b>可空</b>；当前承载 {@value #CHANNEL_MODE_KEY}（{@code MOCK}/{@code SANDBOX}）。
     * 之所以是 {@code Map} 而非专用列：模态是<b>渠道侧的扩展属性</b>，不是 payment 域的一等
     * 字段，用 JSON 载体避免每加一个渠道属性就 ALTER 一次表（FR-300）。</p>
     *
     * <p><b>领域不碰 JSON</b>（FR-302）：本对象只持有 {@code Map}，序列化/反序列化落在
     * {@code infra/persistence}，领域层 MUST NOT 依赖 Jackson。</p>
     */
    private Map<String, String> extra;

    public PaymentAttempt(String paymentNo, String channelCode, int retryCount, long amountMinor, String currencyCode) {
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo");
        this.channelCode = Objects.requireNonNull(channelCode, "channelCode");
        this.amountMinor = amountMinor;
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.requestedAt = Instant.now();
        this.retryCount = retryCount;
    }

    /** 退款渠道尝试（Feature 016 / FR-017 第②步）：复用 payment_attempts，channel_reference=渠道退款流水号。 */
    public static PaymentAttempt refundAttempt(String paymentNo, String channelCode, long amountMinor, String currencyCode) {
        PaymentAttempt attempt = new PaymentAttempt(paymentNo, channelCode, 0, amountMinor, currencyCode);
        attempt.attemptType = TYPE_REFUND;
        return attempt;
    }

    /**
     * 持久化重建：还原一次渠道交互的完整历史（引用/时间/状态/未知信息），绕过创建期状态机
     * （不改变业务规则）。
     */
    public static PaymentAttempt rehydrate(Long id, String paymentNo, String channelCode, int retryCount,
                                           Instant requestedAt, Instant respondedAt, String channelReference,
                                           PaymentAttemptStatus status, String failureReason,
                                           PaymentAttemptErrorType errorType,
                                           Integer version, long amountMinor, String currencyCode) {
        return rehydrate(id, paymentNo, channelCode, retryCount, requestedAt, respondedAt, channelReference,
                status, failureReason, errorType, version, TYPE_PAYMENT, amountMinor, currencyCode);
    }

    /** 全量重建（含尝试类型，Feature 016）。 */
    public static PaymentAttempt rehydrate(Long id, String paymentNo, String channelCode, int retryCount,
                                           Instant requestedAt, Instant respondedAt, String channelReference,
                                           PaymentAttemptStatus status, String failureReason,
                                           PaymentAttemptErrorType errorType,
                                           Integer version, String attemptType, long amountMinor, String currencyCode) {
        return rehydrate(id, paymentNo, channelCode, retryCount, requestedAt, respondedAt, channelReference,
                status, failureReason, errorType, version, attemptType, amountMinor, currencyCode, null);
    }

    /**
     * 全量重建（spec 030 / FR-302）：在 {@link #rehydrate} 之上多还原 {@code extra}
     * （渠道扩展属性，含 {@value #CHANNEL_MODE_KEY}）。
     *
     * <p>保留既有 14 参重载（委托本方法、{@code extra = null}），既有调用点零改动。</p>
     */
    public static PaymentAttempt rehydrate(Long id, String paymentNo, String channelCode, int retryCount,
                                           Instant requestedAt, Instant respondedAt, String channelReference,
                                           PaymentAttemptStatus status, String failureReason,
                                           PaymentAttemptErrorType errorType,
                                           Integer version, String attemptType, long amountMinor,
                                           String currencyCode, Map<String, String> extra) {
        PaymentAttempt attempt = new PaymentAttempt(paymentNo, channelCode, retryCount, amountMinor, currencyCode);
        attempt.id = id;
        attempt.attemptType = attemptType == null ? TYPE_PAYMENT : attemptType;
        attempt.requestedAt = requestedAt;
        attempt.respondedAt = respondedAt;
        attempt.channelReference = channelReference;
        attempt.status = status;
        attempt.failureReason = failureReason;
        attempt.errorType = errorType;
        attempt.version = version;
        attempt.extra = extra;
        return attempt;
    }

    /**
     * 向 {@code extra} 写入一个渠道扩展属性（spec 030 / FR-151）。
     *
     * <p>首次写入时惰性建 Map：无扩展属性的 attempt 保持 {@code extra == null}，
     * 落库即 {@code NULL}，不为「什么都没记」造一个空 JSON 对象。</p>
     */
    public void putExtra(String key, String value) {
        if (this.extra == null) {
            this.extra = new java.util.LinkedHashMap<>();
        }
        this.extra.put(key, value);
    }

    /** 渠道扩展属性（只读视图；无则为 {@code null}）。 */
    public Map<String, String> getExtra() {
        return extra == null ? null : Collections.unmodifiableMap(extra);
    }

    public void setExtra(Map<String, String> extra) {
        this.extra = extra;
    }

    /**
     * <b>只读派生</b>：本次 attempt 的渠道模态（spec 030 / FR-304）——
     * <b>反向路径（查询 / 退款 / 超时扫描）读取模态的唯一入口</b>。
     *
     * <p><b>fail-safe（硬约束，SC-A-11）</b>：以下四类坏数据<b>一律返回 {@link DyeMode#MOCK}</b>，
     * <b>MUST NOT</b> 抛异常中断反向路径，<b>MUST NOT</b> 误判为 {@code SANDBOX}：
     * <ol>
     *   <li>{@code extra == null}（存量行 {@code extra_json IS NULL}，FR-307）；</li>
     *   <li>{@code extra_json} 是非法 JSON（反序列化失败 → 上层传 {@code null}）；</li>
     *   <li>缺 {@value #CHANNEL_MODE_KEY} 键；</li>
     *   <li>键值不在 {@code {MOCK, SANDBOX}} 内。</li>
     * </ol>
     * 判为 MOCK 的后果只是「反向路径不会去连真实渠道沙箱」，最坏是少一次真实调用；
     * 反过来误判为 SANDBOX 会让反向路径去连一个未必存在的真实渠道——后者是资金面风险。</p>
     */
    public DyeMode getChannelMode() {
        if (extra == null) {
            return DyeMode.MOCK;
        }
        String raw = extra.get(CHANNEL_MODE_KEY);
        if (raw == null || raw.isBlank()) {
            return DyeMode.MOCK;
        }
        try {
            return DyeMode.parse(raw);
        } catch (IllegalArgumentException ex) {
            return DyeMode.MOCK; // 非法值：fail-safe 落 MOCK，绝不中断反向路径
        }
    }

    // ---- 状态机 ----

    /** PENDING → ACCEPTED，记录渠道引用与响应时间；非 PENDING 时吸收（返回 false）。 */
    public boolean accept(String channelReference) {
        if (status != PaymentAttemptStatus.PENDING) {
            return false;
        }
        this.channelReference = channelReference;
        this.respondedAt = Instant.now();
        this.status = PaymentAttemptStatus.ACCEPTED;
        return true;
    }

    /**
     * 回填渠道引用（收敛回退路径，fix）：受理时渠道未返回引用（channel_reference 落 NULL）、
     * 回调带引用来收敛时，补齐观测链。仅当引用为空且尝试行在途（PENDING/ACCEPTED/UNKNOWN）
     * 时生效，不改变状态；终态行不回填。
     */
    public boolean backfillChannelReference(String channelReference) {
        if (channelReference == null || this.channelReference != null) {
            return false;
        }
        if (status != PaymentAttemptStatus.PENDING && status != PaymentAttemptStatus.ACCEPTED
                && status != PaymentAttemptStatus.UNKNOWN) {
            return false;
        }
        this.channelReference = channelReference;
        return true;
    }

    /**
     * ACCEPTED/UNKNOWN/PENDING → SUCCEEDED；终态冲突吸收（返回 false）。
     * PENDING 可收敛：收银台路径（ADR-0048 修订版）的尝试在取得渠道引用前即可能收到
     * 权威结果（人工裁定 / 迟到回调），此时尝试语义上仍"在途"，允许直接落终态。
     *
     * <p>成功收敛时清空 {@code failureReason}：受理/UNKNOWN 阶段的占位文案
     * （如 mock 的 "awaiting async callback"）不是终态事实，成功后残留会误导查询方（fix）。</p>
     */
    public boolean succeed() {
        boolean changed = transitionTo(PaymentAttemptStatus.SUCCEEDED, "succeed",
                PaymentAttemptStatus.PENDING, PaymentAttemptStatus.ACCEPTED, PaymentAttemptStatus.UNKNOWN);
        if (changed) {
            this.failureReason = null;
        }
        return changed;
    }

    /** ACCEPTED/UNKNOWN/PENDING → FAILED（权威收敛语义同 {@link #succeed()}）；终态冲突吸收（返回 false）。 */
    public boolean fail(String reason) {
        boolean changed = transitionTo(PaymentAttemptStatus.FAILED, "fail",
                PaymentAttemptStatus.PENDING, PaymentAttemptStatus.ACCEPTED, PaymentAttemptStatus.UNKNOWN);
        if (changed) {
            this.failureReason = reason;
        }
        return changed;
    }

    /** PENDING/ACCEPTED → UNKNOWN（超时/无响应）；终态冲突吸收（返回 false）。 */
    public boolean markUnknown(String reason) {
        if (status == PaymentAttemptStatus.UNKNOWN) {
            return false;
        }
        if (status == PaymentAttemptStatus.SUCCEEDED || status == PaymentAttemptStatus.FAILED) {
            return false; // 迟到未知结果，终态不覆盖
        }
        if (status != PaymentAttemptStatus.PENDING && status != PaymentAttemptStatus.ACCEPTED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal markUnknown from " + this.status);
        }
        this.status = PaymentAttemptStatus.UNKNOWN;
        this.failureReason = reason;
        return true;
    }

    private boolean transitionTo(PaymentAttemptStatus target, String op, PaymentAttemptStatus... from) {
        if (status == target) {
            return false;
        }
        for (PaymentAttemptStatus s : from) {
            if (status == s) {
                this.status = target;
                return true;
            }
        }
        // 终态吸收迟到冲突结果（SUCCEEDED/FAILED 均不可被覆盖）；
        // PENDING 只能经权威结果收敛终态（见 succeed/fail 的 PENDING 来源态）。
        if (status == PaymentAttemptStatus.SUCCEEDED || status == PaymentAttemptStatus.FAILED) {
            return false;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                "illegal " + op + " from " + this.status);
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getChannelCode() {
        return channelCode;
    }

    /** 本次渠道交互金额（最小货币单位，spec 018 / US1 / D2）。 */
    public long getAmountMinor() {
        return amountMinor;
    }

    /** 本次渠道交互币种（spec 018 / US1 / D2）。 */
    public String getCurrencyCode() {
        return currencyCode;
    }

    /** 尝试类型：PAYMENT / REFUND（Feature 016）。 */
    public String getAttemptType() {
        return attemptType;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public String getChannelReference() {
        return channelReference;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /** 记录一次重试（ADR-0014）：重试序号自增，用于观测本次渠道调用实际重放了几轮。 */
    public void recordRetry() {
        this.retryCount++;
    }

    public PaymentAttemptErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(PaymentAttemptErrorType errorType) {
        this.errorType = errorType;
    }
}
