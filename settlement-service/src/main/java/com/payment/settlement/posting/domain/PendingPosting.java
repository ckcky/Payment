package com.payment.settlement.posting.domain;

/**
 * 出站失败台账聚合（spec 031 §12 设计 / 034 §9 实现归属）：
 * 调用方侧的轻量补偿台账——记账 / 通知类后置 RPC 失败时登记一行，
 * 由重试器按退避节奏补投，耗尽置 ABANDONED 等待人工。
 *
 * <p><b>定位红线（031 §12 原文禁令）</b>：台账是<b>补偿辅助</b>，不是资金事实源；
 * 资金事实恒以 ledger_transactions（账本）为准，对账 {@code MISSING_POSTING}（BLOCKER）是最后防线。</p>
 *
 * <p><b>一行一事实</b>：UNIQUE(event_type, source_id) —— 同一事实反复失败只留一行，
 * {@code retryCount} 递增（spec 034 §16 风险 2：retry_count 是唯一计数器，不另设 attempt 列双写）。</p>
 *
 * <p><b>状态机</b>：PENDING →（补投成功）REPOSTED ｜（重试耗尽）ABANDONED；
 * REPOSTED / ABANDONED 均为终态——台账行 MUST 有终止态，不允许无限 PENDING（spec §9.2 纪律）。</p>
 */
public class PendingPosting {

    /** 台账状态：PENDING 待补投 / REPOSTED 补投成功（终态）/ ABANDONED 重试耗尽（终态）。 */
    public enum PostingStatus {
        PENDING, REPOSTED, ABANDONED
    }

    /**
     * 重试耗尽阈值（retry_count 口径 = 已失败投递次数，含登记时那次首投：
     * spec §5「重试 5 次 ⇒ ABANDONED」+ plan §2.2「retry_count 达到 6（首次登记 + 5 次重试
     * 全部失败）→ ABANDONED」）。达到即置 ABANDONED，退出自动补投等待人工 replay。
     */
    public static final int MAX_RETRY_COUNT = 6;

    private Long id;
    private final String eventType;
    private final String sourceType;
    private final String sourceId;
    private final String idempotencyKey;
    private final String payloadJson;
    private String failReason;
    private int retryCount;
    private PostingStatus status;

    /**
     * 新登记一行（登记即代表首投已失败）：retry_count 起始 1（plan §2.2——retry_count 口径
     * 是「已失败投递次数」，首次登记那次失败的投递即计 1）；补投失败在其上递增。
     */
    public PendingPosting(String eventType, String sourceType, String sourceId,
                          String idempotencyKey, String payloadJson) {
        this.eventType = eventType;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.idempotencyKey = idempotencyKey;
        this.payloadJson = payloadJson;
        this.retryCount = 1;
        this.status = PostingStatus.PENDING;
    }

    /** 持久化重建（不改业务规则）。 */
    public static PendingPosting rehydrate(Long id, String eventType, String sourceType, String sourceId,
                                           String idempotencyKey, String payloadJson, String failReason,
                                           int retryCount, PostingStatus status) {
        PendingPosting posting = new PendingPosting(eventType, sourceType, sourceId, idempotencyKey, payloadJson);
        posting.id = id;
        posting.failReason = failReason;
        posting.retryCount = retryCount;
        posting.status = status;
        return posting;
    }

    /**
     * 记一次自动补投失败：{@code retryCount} 递增并刷新失败原因；
     * 达到 {@link #MAX_RETRY_COUNT} 即置 ABANDONED（终态，退出自动补投，等待人工 replay）。
     */
    public void recordRetryFailure(String reason) {
        if (status != PostingStatus.PENDING) {
            return; // 终态行不再消费失败（并发补投的迟到结果幂等吸收）
        }
        this.retryCount++;
        this.failReason = reason;
        if (this.retryCount >= MAX_RETRY_COUNT) {
            this.status = PostingStatus.ABANDONED;
        }
    }

    /**
     * 一次投递成功（自动补投或人工 replay）→ REPOSTED（终态保留，供审计追溯）。
     * ABANDONED 行经人工 replay 成功后同样收口于此（TT-4：人工 replay 可再成功且不产生第二事实）。
     */
    public boolean applyReplaySuccess() {
        if (status == PostingStatus.REPOSTED) {
            return false;
        }
        this.status = PostingStatus.REPOSTED;
        this.failReason = null;
        return true;
    }

    /**
     * 人工 replay 失败的重置（spec 034 §12.1）：回到 PENDING、retry_count 归位到「登记口径」
     * （=1，本次人工投递即已失败的第一次），重新进入完整自动退避；
     * ABANDONED 行只有经人工 replay 入口才能复活（人工可追溯）。
     */
    public void resetForManualReplay(String failureReason) {
        this.retryCount = 1;
        this.status = PostingStatus.PENDING;
        this.failReason = failureReason;
    }

    public boolean isPending() {
        return status == PostingStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getFailReason() {
        return failReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public PostingStatus getStatus() {
        return status;
    }
}
