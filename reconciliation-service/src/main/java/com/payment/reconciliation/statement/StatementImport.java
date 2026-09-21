package com.payment.reconciliation.statement;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.Objects;

/**
 * 账单导入批次聚合根（spec 032 §6.2 / §7.1）：一次导入 = 一个渠道 + 一个周期 + 一份内容指纹。
 *
 * <p>状态机 {@code RECEIVED → NORMALIZED / REJECTED}，不可变（更正账单 = 新导入，绝不就地修状态）。
 * 内容指纹（SHA-256）承担导入层幂等：同指纹重放返回首次导入，零新增差异（§8.3 第一层）。</p>
 */
public class StatementImport {

    private Long id;
    /** 业务单号（SI + 雪花，ADR-0062）。 */
    private String importNo;
    private final String channelCode;
    private final String period;
    /** 来源类型：FILE（本轮）/ API（H-032-5，本轮不做）。 */
    private final String sourceType;
    /** SHA-256（规范化字节流），导入层幂等键的一部分。 */
    private final String contentFingerprint;
    private int rowCount;
    private StatementImportStatus status;
    private String errorReason;
    private final String importedBy;
    private String createdAt;
    private String updatedAt;
    private Integer version;

    public StatementImport(String channelCode, String period, String contentFingerprint, String importedBy) {
        this(channelCode, period, "FILE", contentFingerprint, importedBy);
    }

    public StatementImport(String channelCode, String period, String sourceType,
                           String contentFingerprint, String importedBy) {
        this.channelCode = requireText(channelCode, "channelCode");
        this.period = requireText(period, "period");
        this.sourceType = requireText(sourceType, "sourceType");
        this.contentFingerprint = requireText(contentFingerprint, "contentFingerprint");
        this.importedBy = requireText(importedBy, "importedBy");
        this.importNo = BusinessNos.of(BusinessNoType.STATEMENT_IMPORT);
        this.status = StatementImportStatus.RECEIVED;
        this.rowCount = 0;
    }

    /** 持久化重建：还原聚合与历史状态，绕过创建期校验（不改变业务规则）。 */
    public static StatementImport rehydrate(Long id, String importNo, Integer version, String channelCode,
                                            String period, String sourceType, String contentFingerprint,
                                            int rowCount, StatementImportStatus status, String errorReason,
                                            String importedBy, String createdAt, String updatedAt) {
        StatementImport imprt = new StatementImport(channelCode, period, sourceType, contentFingerprint, importedBy);
        imprt.id = id;
        imprt.importNo = importNo;
        imprt.version = version;
        imprt.rowCount = rowCount;
        imprt.status = status;
        imprt.errorReason = errorReason;
        imprt.createdAt = createdAt;
        imprt.updatedAt = updatedAt;
        return imprt;
    }

    // ---- 状态机（唯一状态变更入口）----

    /** RECEIVED → NORMALIZED：全部行解析成功，登记行数。 */
    public void normalize(int rowCount) {
        requireStatus(StatementImportStatus.RECEIVED, "normalize");
        this.rowCount = rowCount;
        this.status = StatementImportStatus.NORMALIZED;
    }

    /** RECEIVED → REJECTED（不可逆）：结构性解析失败，整批拒绝 + 原因（不产生半套差异）。 */
    public void reject(String reason) {
        requireStatus(StatementImportStatus.RECEIVED, "reject");
        this.status = StatementImportStatus.REJECTED;
        this.errorReason = Objects.requireNonNull(reason, "reason");
    }

    private void requireStatus(StatementImportStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, field + " must not be blank");
        }
        return value;
    }

    public boolean normalized() {
        return status == StatementImportStatus.NORMALIZED;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getImportNo() {
        return importNo;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public String getPeriod() {
        return period;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public int getRowCount() {
        return rowCount;
    }

    public StatementImportStatus getStatus() {
        return status;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public String getImportedBy() {
        return importedBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
