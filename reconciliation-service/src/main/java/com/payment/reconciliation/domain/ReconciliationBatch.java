package com.payment.reconciliation.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

import java.util.List;
import java.util.Objects;

/**
 * 对账批次聚合根：某个周期内平台事实与渠道账单的比对结果（匹配 + 差异）。
 *
 * <p>状态机：PENDING → RECONCILING → CONSISTENT / HAS_DIFFERENCE；
 * HAS_DIFFERENCE → PROCESSING → CLOSED；CONSISTENT → CLOSED。
 * 状态迁移只经领域方法，金额由事实（PlatformFact/ChannelStatement）携带，禁止浮点。</p>
 */
public class ReconciliationBatch {

    private Long id;
    private Integer version;
    private final String period;
    private final String source;
    private ReconciliationStatus status = ReconciliationStatus.PENDING;
    private List<Match> matches = List.of();
    private List<Difference> differences = List.of();

    public ReconciliationBatch(String period, String source) {
        this.period = Objects.requireNonNull(period, "period");
        if (period.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "period must not be blank");
        }
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 持久化重建：还原聚合与历史状态，绕过创建期校验（不改变业务规则）。 */
    public static ReconciliationBatch rehydrate(Long id, Integer version, String period, String source,
                                                ReconciliationStatus status, List<Match> matches,
                                                List<Difference> differences) {
        ReconciliationBatch batch = new ReconciliationBatch(period, source);
        batch.id = id;
        batch.version = version;
        batch.status = status;
        batch.matches = List.copyOf(matches == null ? List.of() : matches);
        batch.differences = List.copyOf(differences == null ? List.of() : differences);
        return batch;
    }

    // ---- 状态机（唯一状态变更入口）----

    /** PENDING → RECONCILING。 */
    public void start() {
        requireStatus(ReconciliationStatus.PENDING, "start");
        this.status = ReconciliationStatus.RECONCILING;
    }

    /** RECONCILING → CONSISTENT（无差异）/ HAS_DIFFERENCE（有差异）。 */
    public void finish(List<Match> matches, List<Difference> differences) {
        requireStatus(ReconciliationStatus.RECONCILING, "finish");
        this.matches = List.copyOf(matches == null ? List.of() : matches);
        this.differences = List.copyOf(differences == null ? List.of() : differences);
        this.status = this.differences.isEmpty()
                ? ReconciliationStatus.CONSISTENT
                : ReconciliationStatus.HAS_DIFFERENCE;
    }

    /** HAS_DIFFERENCE → PROCESSING。 */
    public void beginProcessing() {
        requireStatus(ReconciliationStatus.HAS_DIFFERENCE, "beginProcessing");
        this.status = ReconciliationStatus.PROCESSING;
    }

    /** CONSISTENT / PROCESSING → CLOSED。 */
    public void close() {
        if (status == ReconciliationStatus.CONSISTENT || status == ReconciliationStatus.PROCESSING) {
            this.status = ReconciliationStatus.CLOSED;
            return;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal close from " + this.status);
    }

    private void requireStatus(ReconciliationStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public String getPeriod() {
        return period;
    }

    public String getSource() {
        return source;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public List<Match> getMatches() {
        return matches;
    }

    public List<Difference> getDifferences() {
        return differences;
    }
}
