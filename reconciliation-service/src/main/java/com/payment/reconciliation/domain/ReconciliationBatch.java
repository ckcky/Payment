package com.payment.reconciliation.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.List;
import java.util.Objects;

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
    /** 业务单号（RB + 雪花，ADR-0062）。 */
    private String batchNo;
    private Integer version;
    private final String period;
    private final String source;
    private ReconciliationStatus status = ReconciliationStatus.PENDING;
    private List<Match> matches = List.of();
    private List<Difference> differences = List.of();
    /** 本批对账比对的渠道账单来源溯源（ADR-0020）；随批次持久化，供事后追溯。 */
    private ChannelStatementSource statementSource;
    /** 关闭操作人与关闭时间（ISO-8601），仅 CLOSED 态有意义（ADR-0019）。 */
    private String closedBy;
    private String closedAt;

    public ReconciliationBatch(String period, String source) {
        this.period = Objects.requireNonNull(period, "period");
        if (period.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "period must not be blank");
        }
        this.source = Objects.requireNonNull(source, "source");
        this.batchNo = BusinessNos.of(BusinessNoType.RECONCILIATION_BATCH);
    }

    /** 持久化重建：还原聚合与历史状态，绕过创建期校验（不改变业务规则）。 */
    public static ReconciliationBatch rehydrate(Long id, String batchNo, Integer version, String period, String source,
                                                ReconciliationStatus status, List<Match> matches,
                                                List<Difference> differences, ChannelStatementSource statementSource,
                                                String closedBy, String closedAt) {
        ReconciliationBatch batch = new ReconciliationBatch(period, source);
        batch.id = id;
        batch.batchNo = batchNo;
        batch.version = version;
        batch.status = status;
        batch.matches = List.copyOf(matches == null ? List.of() : matches);
        batch.differences = List.copyOf(differences == null ? List.of() : differences);
        batch.statementSource = statementSource;
        batch.closedBy = closedBy;
        batch.closedAt = closedAt;
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

    /** HAS_DIFFERENCE → PROCESSING；PROCESSING 为幂等空操作（ADR-0019）。 */
    public void beginProcessing() {
        if (status == ReconciliationStatus.PROCESSING) {
            return;
        }
        requireStatus(ReconciliationStatus.HAS_DIFFERENCE, "beginProcessing");
        this.status = ReconciliationStatus.PROCESSING;
    }

    /**
     * CONSISTENT / PROCESSING → CLOSED（ADR-0019）。
     * 关闭门禁：尚有未处理差异 ⇒ 抛 {@code UNRESOLVED_DIFFERENCES}；CLOSED → CLOSED 幂等空操作。
     * 「收口」是审计动作，由资金运营显式调用，不自动触发。
     */
    public void close(String operator, String closedAtIso) {
        if (status == ReconciliationStatus.CLOSED) {
            return;
        }
        if (unresolvedDifferenceCount() > 0) {
            throw BizException.of(ErrorCodes.UNRESOLVED_DIFFERENCES,
                    "cannot close batch with " + unresolvedDifferenceCount() + " unresolved differences");
        }
        if (status == ReconciliationStatus.CONSISTENT || status == ReconciliationStatus.PROCESSING) {
            this.status = ReconciliationStatus.CLOSED;
            this.closedBy = operator;
            this.closedAt = closedAtIso;
            return;
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal close from " + this.status);
    }

    /** 未处理差异数（ADR-0019 关闭门禁口径）。 */
    public long unresolvedDifferenceCount() {
        return differences.stream().filter(d -> !d.isResolved()).count();
    }

    private void requireStatus(ReconciliationStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    /** 应用层在比对完成后登记账单来源（ADR-0020 溯源）。 */
    public void setStatementSource(ChannelStatementSource statementSource) {
        this.statementSource = statementSource;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getBatchNo() {
        return batchNo;
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

    public ChannelStatementSource getStatementSource() {
        return statementSource;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public String getClosedAt() {
        return closedAt;
    }
}
